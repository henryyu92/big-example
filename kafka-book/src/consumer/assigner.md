## 分区分配
消费者客户端拉取的是订阅主题中特定分区的消息，Kafka 消费者内部维护了分区分配的策略，使得主题中的分区能够均匀的分配给消费组内的消费者。

消费者拉取消息时根据设置的分区分配算法计算拉取的分区，然后从指定的分区中拉取消息。Kafka 提供了 `ConsumerPartitionAssignor` 接口定义消费者客户端的分区分配策略：
```java
// 根据 Metadata 和订阅信息进行分区分配的算法
GroupAssignment assign(Cluster metadata, GroupSubscription groupSubscription);

// 返回表示分区器唯一标识的名字
String name();
```
实现自定义分区算法时通常继承 `AbstractPartitionAssignor` 并重写 `assign` 方法。Kafka 默认使用 `RangeAssigner` 策略分配分区，显式指定分区策略时可以通过 Kafka 提供的消费者客户端参数 `partition.assignment.strategy` 设置：
```java
properties.setProperty("partition.assignment.strategy", "partition-assignment-strategy-class");
```

## RangeAssignor
`RangeAssigor` 是 Kafka 的默认分区策略，其思想是按照消费者的数量计算每个消费者分配的分区数，然后将消费者排序之后依次分配。
```java
public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                Map<String, Subscription> subscriptions) {
    // 每个主题的消费者
    Map<String, List<MemberInfo>> consumersPerTopic = consumersPerTopic(subscriptions);

    // 初始化每个消费者的分区分配集合
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for (String memberId : subscriptions.keySet())
        assignment.put(memberId, new ArrayList<>());

    for (Map.Entry<String, List<MemberInfo>> topicEntry : consumersPerTopic.entrySet()) {
        String topic = topicEntry.getKey();
        List<MemberInfo> consumersForTopic = topicEntry.getValue();

        // 主题的分区数
        Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
        if (numPartitionsForTopic == null)
            continue;
        // 消费者排序(根据 groupId 和 memberId 排序)
        Collections.sort(consumersForTopic);
        // 计算每个消费者分配的分区数
        int numPartitionsPerConsumer = numPartitionsForTopic / consumersForTopic.size();
        int consumersWithExtraPartition = numPartitionsForTopic % consumersForTopic.size();

        List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
        for (int i = 0, n = consumersForTopic.size(); i < n; i++) {
            int start = numPartitionsPerConsumer * i + Math.min(i, consumersWithExtraPartition);
            int length = numPartitionsPerConsumer + (i + 1 > consumersWithExtraPartition ? 0 : 1);
            assignment.get(consumersForTopic.get(i).memberId).addAll(partitions.subList(start, start + length));
        }
    }
    return assignment;
}
```
当消费者数量超过主题的分区数时，`RangeAssginor` 分区策略会导致大于分区数的消费者无法分配到分区，另外当消费者订阅多个主题的时候会导致某些消费者由于分配了较多的分区负载非常重而某些消费者无法分配到分区而空闲。

例如：有两个消费者 C0 和 C1 都订阅了主题 t0 和 t1，每个主题有 3 个分区，也就是 t0p0、t0p1、t0p2、t1p0、t1p1、t1p2。那么 C0 分配到的分区为 t0p0、t0p1、t1p0、t1p1，C1 分配到的分区为 t0p2、t1p2
## RoundRobinAssignor
`RoundRobinAssignor` 分配策略列出所有可用分区和所有可用消费者，然后从分区到消费者进行循环分配。如果所有消费者订阅的主题相同那么分区的分配是均匀的，如果消费者订阅的主题不同则未订阅主题的消费者跳过而不分配分区。
```java
public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                Map<String, Subscription> subscriptions) {
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    List<MemberInfo> memberInfoList = new ArrayList<>();
    for (Map.Entry<String, Subscription> memberSubscription : subscriptions.entrySet()) {
        assignment.put(memberSubscription.getKey(), new ArrayList<>());
        // 所有的消费者
        memberInfoList.add(new MemberInfo(memberSubscription.getKey(),
                                          memberSubscription.getValue().groupInstanceId()));
    }
    // 所有消费者排序
    CircularIterator<MemberInfo> assigner = new CircularIterator<>(Utils.sorted(memberInfoList));

    for (TopicPartition partition : allPartitionsSorted(partitionsPerTopic, subscriptions)) {
        final String topic = partition.topic();
        // 如果消费者订阅了分区对应主题则分配
        while (!subscriptions.get(assigner.peek().memberId).topics().contains(topic))
            assigner.next();
        assignment.get(assigner.next().memberId).add(partition);
    }
    return assignment;
}
```
- 消费者订阅相同：有两个消费者 C0 和 C1 都订阅了主题 t0 和 t1，每个主题有 3 个分区，也就是 t0p0、t0p1、t0p2、t1p0、t1p1、t1p2。那么 C0 分配到的分区为 t0p0、t0p2、t1p1，C1 分配到的分区为 t0p1、t1p0、t1p2
- 消费者订阅不同：有三个消费者 C0, C1 和 C2，三个主题 t0、t1 和 t2，分别有 1, 2, 3 个分区，也就是 t0p0、t1p0、t1p1、t2p0、t2p1、t2p2，C0 订阅了 t0，C1 订阅了 t0 和 t1，C2 订阅了 t0、t1、t2。那么 C0 分配到的分区为 t0p0，C1 分配到的分区为 t1p0，C2 分配到的分区为 t1p1、t2p0、t2p1、t2p2

## StickyAssignor

`StickyAssignor` 分区策略能够保证消费者分配的分区尽可能均衡，StickyAssignor 分区策略有两个目的：
- 分区的分配要尽可能均匀，即消费者分配到的分区数量相差最多只有 1 个
- 当发生分区重分配时，分区的分配尽可能与之前的分配保持同步
  

例如有三个消费者 C0, C1 和 C2 都订阅了四个主题 t0, t1, t2, t3 并且每个主题有两个分区，即 t0p0, t0p1, t1p0, t1p1, t2p0, t2p1, t3p0, t3p1。那么 C0 分配到 t0p0, t1p1, t3p0，C1 分配到 t0p1, t2p0, t3p1，C2 分配到 t1p0, t2p1。如果消费者 C1 故障导致消费组发生再均衡操作，此时消费分区会重新分配，则 C0 分配到 t0p0, t1p1, t3p0, t2p0 而 C2 分配到 t1p0, t2p1, t0p1, t3p1

