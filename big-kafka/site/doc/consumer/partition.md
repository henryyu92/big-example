### 消费分区
Kafka 消费者

同一个消费组中的消费者消费不同分区的消息，不同消费组消费相同的消息

### 分区分配策略
Kafka 提供了消费者客户端参数 ```partition.assignment.strategy``` 参数设置消费者与订阅主题之间的分区分配策略，默认情况下是 ```org.apache.kafka.clients.consumer.RangeAssignor```，除此之外 Kafka 还提供 RoundRobinAssignor 和 StickyAssignor 两种分区分配策略，消费者客户端的分区策略参数可以配置多种分区分配策略。
#### RangeAssignor
RangeAssignor 分配策略是基于单个主题的，对于每个主题将分区按照数字顺序排序，将消费者按照字典顺序排序，然后将分区数除以消费者总数以确定要分配给每个消费者的分区数并将分区分配给对应的消费者。如果没有均匀分配，即分区数没有被消费者总数整除，那么前面的消费者将会多分配一个分区。

例如：有两个消费者 C0 和 C1 都订阅了主题 t0 和 t1，每个主题有 3 个分区，也就是 t0p0、t0p1、t0p2、t1p0、t1p1、t1p2。那么 C0 分配到的分区为 t0p0、t0p1、t1p0、t1p1，C1 分配到的分区为 t0p2、t1p2
```java
public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                Map<String, Subscription> subscriptions) {
    Map<String, List<String>> consumersPerTopic = consumersPerTopic(subscriptions);
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for (String memberId : subscriptions.keySet())
        assignment.put(memberId, new ArrayList<>());

    for (Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()) {
        String topic = topicEntry.getKey();
        List<String> consumersForTopic = topicEntry.getValue();

        Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
        if (numPartitionsForTopic == null)
            continue;

        Collections.sort(consumersForTopic);

        int numPartitionsPerConsumer = numPartitionsForTopic / consumersForTopic.size();
        int consumersWithExtraPartition = numPartitionsForTopic % consumersForTopic.size();

        List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
        for (int i = 0, n = consumersForTopic.size(); i < n; i++) {
            int start = numPartitionsPerConsumer * i + Math.min(i, consumersWithExtraPartition);
            int length = numPartitionsPerConsumer + (i + 1 > consumersWithExtraPartition ? 0 : 1);
            assignment.get(consumersForTopic.get(i)).addAll(partitions.subList(start, start + length));
        }
    }
    return assignment;
}
```
#### RoundRobinAssignor
RoundRobinAssignor 分配策略列出所有可用分区和所有可用消费者，然后从分区到消费者进行循环分配。如果所有消费者订阅的主题相同那么分区的分配是均匀的，如果消费者订阅的主题不同则未订阅主题的消费者跳过而不分配分区。
- 消费者订阅相同：有两个消费者 C0 和 C1 都订阅了主题 t0 和 t1，每个主题有 3 个分区，也就是 t0p0、t0p1、t0p2、t1p0、t1p1、t1p2。那么 C0 分配到的分区为 t0p0、t0p2、t1p1，C1 分配到的分区为 t0p1、t1p0、t1p2
- 消费者订阅不同：有三个消费者 C0, C1 和 C2，三个主题 t0、t1 和 t2，分别有 1, 2, 3 个分区，也就是 t0p0、t1p0、t1p1、t2p0、t2p1、t2p2，C0 订阅了 t0，C1 订阅了 t0 和 t1，C2 订阅了 t0、t1、t2。那么 C0 分配到的分区为 t0p0，C1 分配到的分区为 t1p0，C2 分配到的分区为 t1p1、t2p0、t2p1、t2p2
```java
public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                Map<String, Subscription> subscriptions) {
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for (String memberId : subscriptions.keySet())
        assignment.put(memberId, new ArrayList<>());

    CircularIterator<String> assigner = new CircularIterator<>(Utils.sorted(subscriptions.keySet()));
    for (TopicPartition partition : allPartitionsSorted(partitionsPerTopic, subscriptions)) {
        final String topic = partition.topic();
        while (!subscriptions.get(assigner.peek()).topics().contains(topic))
            assigner.next();
        assignment.get(assigner.next()).add(partition);
    }
    return assignment;
}
```
#### StickyAssignor
StickyAssignor 分区策略有两个目的：
- 分区的分配要尽可能均匀，即消费者分配到的分区数量相差最多只有 1 个
- 当发生分区重分配时，分区的分配尽可能与之前的分配保持同步
  
例如有三个消费者 C0, C1 和 C2 都订阅了四个主题 t0, t1, t2, t3 并且每个主题有两个分区，即 t0p0, t0p1, t1p0, t1p1, t2p0, t2p1, t3p0, t3p1。那么 C0 分配到 t0p0, t1p1, t3p0，C1 分配到 t0p1, t2p0, t3p1，C2 分配到 t1p0, t2p1。如果消费者 C1 故障导致消费组发生再均衡操作，此时消费分区会重新分配，则 C0 分配到 t0p0, t1p1, t3p0, t2p0 而 C2 分配到 t1p0, t2p1, t0p1, t3p1

#### 自定义分区分配策略
自定义分区分配策略需要实现 ```org.apache.kafka.clients.consumer.internal.PartitionAssignor``` 接口或者继承 ```org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor``` 类。

PartitionAssignor 接口中定义了两个内部类：Subscription 和 Assignment。

Subscription 用来表示消费者的订阅信息，topics 表示消费者的订阅主题列表，userData 表示用户自定义信息：
```java
class Subscription {
  private final List<String> topics;
  private final ByteBuffer userData;
  // ...
}
```
PartitionAssignor 接口通过 subscription 方法来设置消费者自身相关的 Subscription 信息。

Assignment 类用来表示分配结果信息，partition 表示所分配到的分区集合，userData 表示用户自定义的数据：
```java
class Assignment {
  private final List<TopicPartition> partitions;
  private final ByteBuffer userData;
  // ...
}
```
PartitionAssignor 接口中的 onAssignment 方法是在每个消费者收到消费组 leader 分配结果时的回调函数。

真正的分区分配方案的实现是在 assign 方法中，方法中的参数 metadata 表示集群的元数据信息，而 subscriptions 表示消费组内各个消费者成员的订阅信息，方法返回各个消费者的分配信息。

```java
public class RandomAssignor extends AbstractPartitionAssignor{
  public String name(){
    return "random";
  }
  public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic, Map<String, Subscription> subscriptions){
    Map<String, List<String>> consumersPerTopic = consumersPerTopic(subscriptions);
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for(String memberId : subscriptions.keySet()){
      assignment.put(memberId, new ArrayList<>());
    }

    for(Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()){
      String topic = topicEntry.getKey();
      List<String> consumersForTopic = topicEntry.getValue();
      int consumerSize = consuemrForTopic.size();

      Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
      if(numPartitionsForTopic == null){
        continue;
      }

      List<TopicPartition> partitions = AbstractPartitionAssinor.partitions(topic, numPartitionsForTopic);
      for(TopicPartition partition : partitions){
        int rand = new Random().nextInt(consumerSize);
        String randomConsumer = consumersForTopic.get(rand);
        assignment.get(randomConsumer).add(partition);
      }
    }
    return assignment;
  }

  private Map<String, List<String>> consumersPerTopic(Map<String, Subscription> consumerMetadata){
    Map<String, List<String>> res = new HashMap<>();
    for(Map.Entry<String, Subscription> subscriptionEntry : consumerMetadata.entrySet()){
      String consumerId = subscriptionEntry.getKey();
      for(String topic : subscriptionEntry.getValue().topics()){}
        put(res, topic, consuemrId)
    }
    return res;
  }
}
```
在使用自定义分区分配时，需要在消费者客户端添加配置参数：
```java
properties.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RandomAssignor.class.getName());
```
通过自定义分区策略可以使一个分区分配给同一消费组内的多个消费者，继而实现消费组内广播的功能：
```java
public class BroadcastAssigner extends AbstractPartitionAssinger{
  
}
```
通过自定义分区器，可以实现在同一个消费组中的不同消费者消费同一个分区中的消息，但是同一个消费组中的消费者不能消费同一个消息。

####  消费者协调器
当消费者配置了不同的分区策略，多个消费者之间的分区分配需要通过消费者协调器(ConsumerCoordinator) 和组协调器(GroupCoordinator) 来完成。

Kafka 旧版本客户端使用 ZooKeeper 监听完成消费者分区分配之间的协调工作。每个消费组在 ZK 上维护了 /consumer/<group> 路径，其下有三个子路径：
- ids：使用临时节点存储消费组中的消费者信息，临时节点路径名为 consumer.id_主机名-时间戳-UUID 
- owners：记录分区和消费者的对应关系
- offsets：记录消费组中消息的 offset

每个 broker、topic 和 partition 在 ZK 上


### 再均衡

再均衡是指分区的所属权从一个消费者转移到另一个消费者的行为，它为消费组具备高可用性和伸缩性提供保障，使得可以方便安全的删除或添加消费组中的消费者。

再均衡发生期间消费组内的消费者是无法消费消息的，也就是在再均衡发生期间消费组不可用；再均衡之后会丢失消费者对分区持有的状态(如消费位移)。

每个消费组的子集在服务端对应一个 GroupCoordinator 对其进行管理，消费者客户端中的 ConsumerCoordinator 负责与 broker 端的 GroupCoordinator 进行交互。

触发再均衡的情况：
- 新的消费者加入消费组
- 消费者下线，消费者遇到长时间 GC、网络延时导致消费者长时间未向 GroupCoordinator 发送心跳等情况时会被认为下线
- 消费者退出消费组(发送 LeaveGroupRequest 请求)，比如消费者客户端调用 unsubscribe 发那个发取消订阅
- 消费组对应的 GroupCoordinator 节点发生变更
- 消费组内所有订阅的任一主题或者主题的分区数量发生变化

Kafka 服务端提供 GroupCoordinator 组件用于管理消费组，而消费者客户端的 ConsumerCoordinator 组件负责与 GroupCoordinator 进行交互。

当有消费者加入消费组时，消费者、消费组和组协调器之间会经历四个阶段：
##### 第一阶段(FIND_COORDINATOR)
消费者需要确定它所属的消费组对应的 GroupCoordinator 所在的 broker，并创建与该 broker 相互通信的网络连接。如果消费者已经保存了与消费组对应的 GroupCoordinator 节点的信息，并且与它之间的网络连接是正常的，那么就可以进入第二阶段，否则就需要向集群中的负载最小的节点发送 FindCoordinatorRequest 请求来查找对应的 GroupCoordinator。

FindCoordinatorRequest 请求体中只有两个域：coordinator_key 和 coordinator_type。coordinator_key 在这里就是消费组的名称 groupId，coordinator_type 设置为 0。Kafka 在收到 FindCoordinatorRequest 请求之后会根据 coordinator_key 查找对应的 GroupCoordinator 节点，如果找到对应的 GroupCoordinator 则会返回其相对应的 node_id、host 和 port 信息。

查找 GroupCoordiantor 的方式是先根据消费组 groupId 的哈希值计算 __consumer_offset 中的分区编号：```Utils.abs(groupId.hashCode) % groupMetadataTopicPartitionCount```，groupMetadataTopicPartitionCount 为主题 __consumer_offsets 的分区个数，可以通过 broker 端参数 offsets.topic.num.partitions 来配置，默认值是 50。

找到对应的 __consumer_offsets 中的分区之后，再寻找此分区 leader 副本所在的 broker 节点，该 broker 节点即为这个 groupId 所对应的 GroupCoordinator 节点。消费者 groupId 最终的分区分配方案及组内消费者所提交的消费位移信息都会发送给此分区 leader 副本所在的 broker 节点，让此 broker 节点既扮演 GroupCoordinator 的角色，又扮演保存分区分配方案和组内消费者位移的角色，这样可以省去很多不必要的中间轮转所带来的开销。
##### 第二阶段(JOIN_GROUP)
在成功找到消费组所对应的 GroupCoordiantor 之后就进入了加入消费组的阶段，在此阶段的消费者会向 GroupCoordinator 发送 JoinGroupRequest 请求并处理响应。

JoinGroupRequest 的请求体包含多个域：
- group_id 是消费组的 id
- session_timeout 对应消费端参数 ```session.timeout.ms```，GroupCoordinator 超过设置的时间内没有收到心跳报文则认为此消费者已经下线
- rebalance_timeout 对应消费端参数 ```max.poll.interval.ms```，表示当消费者再平衡的时候，GroupCoordinator 等待各个消费者重新加入的最长等待时间
- member_id 表示 GroupCoordinator 分配给消费者的 id 标识。第一次发送 JoinGroupRequest 请求的时候此字段设置为 null
- protocol_type 表示消费组实现的协议，对于消费者而言此字段值为 consumer

JoinGroupRequest 中的 group_protocol 域为数组类型，其中可以囊括多个分区分配策略，这个主要取决于消费者客户端参数 ```partition.assignment.strategy``` 的配置。如果配置了多种策略，那么 JoinGroupRequest 请求中就会包含多个 protocol_name 和 protocol_metadata。protocol_name 对应 PartitionAssignor 接口中 name 方法设置的值，protocol_metadata 是一个 byte 类型，其实质还可以更细粒度地划分为 version、topic 和 user_data。

version 占 2 个字节，目前其固定值为 0；topics 对应的 PartitionAssignor 接口的 subscription 方法返回值类型 Subscription 中的 topics，代表一个主题列表；user_data 对应 Subscription 中的 userData 可以为空。

如果是原有的消费者重新加入消费组，那么在真正发送 JoinGrouupRequest 请求之前还要执行一些准备工作：
- 如果消费端参数 enable.auto.commit 设置为 ture(默认为 true)，即开启自动提交位移功能，那么在请求加入消费组之前需要向 GroupCoordinator 提交消费位移。这个过程是阻塞执行的，要么成功提交消费位移，要么超时
- 如果消费者添加了自定义的再均衡监听器(ConsumerRebalanceListener)，那么此时会调用 onPartitionsRevoked 方法在重新加入消费组之前实施自定义的规则逻辑
- 因为是重新加入消费者组，之前与 GroupCoordinator 节点之间的心跳检测也就不需要了，所以在成功的重新加入消费组之前需要禁止心跳检测的运作

消费者在发送 JoinGroupRequest 请求之后会阻塞等待 Kafka 服务端的响应，服务端在收到 JoinGroupCoordinator 请求后会交由 GroupCoordinator 来进行处理，GroupCoordiantor 首先会对 JoinGroupRequest 进行合法性校验，如果消费者是第一次请求加入消费组，那么 JoinGroupRequest 请求中的 member_id 是 null，此时组协调器负责为此消费者生成一个 member_id，生成规则为 clientId 和 UUID 拼接而成

GroupCoordinator 需要为消费组内的消费者选举出一个 leader，选举算法为：如果组内还没有 leader 则第一个加入消费组的消费者即为 leader，如果 leader 退出导致的重新选举则从存储了消费者的 map 中选取第一个为 leader

leader 选取完毕之后需要选举分区分配策略，这个策略的选举是根据各个消费者支持的分区消费策略投票而决定，选举的过程如下：
- 收集各个消费者支持的所有分配策略组成候选集 candidates
- 每个消费者从后选举中找出第一个自身支持的策略并投票
- 计算候选集中各个策略的选票，选取选票最多的策略为当前消费组的分配策略

确定了消费组内的消费者 leader 和消费组的分区策略之后，Kafka 服务端发送 JoinGroupResponse 响应给各个消费者，leader 消费者和普通消费者的区别在于 leader 消费者的 members 字段包好消费组内消费者的成员信息包括选举出的分区分配策略。

##### 第三阶段(SYNC_GROUP)
leader 消费者根据选举出的分区分配策略实施具体的分区分配，在此之后需要将分配方案同步给各个消费者。leader 消费者并不是直接和其他消费者同步分配方法，而是通过 GroupCoordinator 实现分配方案同步。各个消费者向 GroupCoordinator 发送 SyncGrouupRequest 请求来同步分配方案，只有 leader 消费者发送的 SyncGrouupRequest 请求中包含具体的分区分配方案，这个分配方案保存在 group_assignment 中。

服务端在收到消费者发送的 SyncGroupRequest 请求之后会交由 GroupCoordinator 负责具体的逻辑处理。
GroupCoordinator 对 SyncGroupRequest 做合法校验之后将 leader 消费者发送的分配方案提取出来连同真个消费组的元数据信息一起存入 Kafka 的 ```__consumer_offsets``` 主题中，最后发送响应给各个消费者以提供各个消费者各自所属的分配方案。

当消费者收到所属的分配方案之后会调用 PartitionAssignor 中的 onAssignment 方法，随后再调用 ConsumerRebalanceListener 中的 onPartitionAssigned 方法，之后开启心跳任务，消费者定期向服务端的 GroupCoordinator 发送 HeartbeatRequest

##### 第四阶段(HEATBEAT)
在正式消费前，消费者需要确定拉取消息的起始位置，如果已经将最后的消费位移提交了 GroupCoordinator 并保存到了 __consumer_offsets 主题中，此时消费者可以通过 OffsetFetchRequest 请求获取上次提交的消费位移并从此处继续消费。

消费者向 GroupCoordinator 发送心跳来维持分区的所有权关系。心跳线程是一个独立的线程，如果消费者停止发送心跳的时间足够长则整个会话被判定为过期，GroupCoordinator 会认为这个消费者已经死亡，也就会触发一次再均衡行为。消费者心跳间隔由参数 ```heartbeat.interval.ms``` 指定，默认 3000。

如果消费者发送崩溃并停止读取消息，那么 GroupCoordiantor 会等待一段时间确认这个消费者死亡之后才会触发再均衡，这段时间由参数 ```session.timeout.ms``` 指定，这个参数必须配置在 broker 端参数 ```group.min.session.timeout.ms```(默认 6000) 和 ```group.max.session.timeout.ms```(默认 300000) 允许的范围内。

参数 ```max.poll.interval.ms``` 用于指定 poll 方法调用之间的最大延时，也就是消费者在获取更多消息之前可以空闲的时间最大值，如果超过此时间上限没有 poll 方法调用则任务消费者失败触发再均衡。

除了被动退出消费组，还可以向 GroupCoordinator 发送 LeaveGroupRequest 请求主动退出消费组，如在消费者客户端调用 unsubscribe 方法。