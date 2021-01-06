# 元数据更新

Kafka 元数据指的是集群的信息，包括主题、分区、节点等信息，生产者客户端发送消息时根据元数据信息将消息发送到分区对应的 Broker 上。Kafka 元数据信息由 `MetadataCache` 表示，元数据的操作由 `Metadata` 完成，`MetadataCache` 包含的实体类 `Cluster` 则保存了集群的相关信息。
```java
public final class Cluster {
    
    // 集群节点
    private final List<Node> nodes;
    // 未校验的主题
    private final Set<String> unauthorizedTopics;
    // 无效主题
    private final Set<String> invalidTopics;
    // 内部主题
    private final Set<String> internalTopics;
    // 控制器节点
    private final Node controller;
    // 分区信息
    private final Map<TopicPartition, PartitionInfo> partitionsByTopicPartition;
    // 主题-分区信息
    private final Map<String, List<PartitionInfo>> partitionsByTopic;
    // 主题的可用分区信息
    private final Map<String, List<PartitionInfo>> availablePartitionsByTopic;
    // 节点保存的分区
    private final Map<Integer, List<PartitionInfo>> partitionsByNode;
    // broker-节点关系
    private final Map<Integer, Node> nodesById;
    // 集群信息
    private final ClusterResource clusterResource;
}
```
元数据对象在生产者客户端创建的时候初始化，客户端在发送消息时通过 `waitOnMetadata` 方法获取集群的元数据信息：
```java
private ClusterAndWaitTime waitOnMetadata(String topic, Integer partition, long maxWaitMs) throws InterruptedException {
    Cluster cluster = metadata.fetch();
    if (cluster.invalidTopics().contains(topic))
        throw new InvalidTopicException(topic);
    // 如果 topic 新加入则 Metadata 的更新标识设置为 true
    metadata.add(topic);

    // 如果 Metadata 包含 topic 和分区信息则直接返回
    Integer partitionsCount = cluster.partitionCountForTopic(topic);
    if (partitionsCount != null && (partition == null || partition < partitionsCount))
        return new ClusterAndWaitTime(cluster, 0);

    long begin = time.milliseconds();
    long remainingWaitMs = maxWaitMs;
    long elapsed;
    // Issue metadata requests until we have metadata for the topic and the requested partition,
    // or until maxWaitTimeMs is exceeded. This is necessary in case the metadata
    // is stale and the number of partitions for this topic has increased in the meantime.
    do {
        if (partition != null) {
            log.trace("Requesting metadata update for partition {} of topic {}.", partition, topic);
        } else {
            log.trace("Requesting metadata update for topic {}.", topic);
        }
        metadata.add(topic);
        int version = metadata.requestUpdate();
        // 唤醒 Sender 线程发送 MetadataRequest
        sender.wakeup();
        try {
            // 阻塞等待元数据更新直到超时，数据更新通过 version 判断
            metadata.awaitUpdate(version, remainingWaitMs);
        } catch (TimeoutException ex) {
            // Rethrow with original maxWaitMs to prevent logging exception with remainingWaitMs
            throw new TimeoutException(
                    String.format("Topic %s not present in metadata after %d ms.",
                            topic, maxWaitMs));
        }
        cluster = metadata.fetch();
        elapsed = time.milliseconds() - begin;
        if (elapsed >= maxWaitMs) {
            throw new TimeoutException(partitionsCount == null ?
                    String.format("Topic %s not present in metadata after %d ms.",
                            topic, maxWaitMs) :
                    String.format("Partition %d of topic %s with partition count %d is not present in metadata after %d ms.",
                            partition, topic, partitionsCount, maxWaitMs));
        }
        metadata.maybeThrowExceptionForTopic(topic);
        remainingWaitMs = maxWaitMs - elapsed;
        // 再次判断 partition 是否满足
        partitionsCount = cluster.partitionCountForTopic(topic);
    } while (partitionsCount == null || (partition != null && partition >= partitionsCount));

    return new ClusterAndWaitTime(cluster, elapsed);
}
```
Kafka 生产者客户端在发送消息时如果元数据没有 topic 以及 partition 的信息则会阻塞的更新元数据信息，获取元数据的整个过程时长由客户端参数 `metadata.max.age.ms`(默认 5m)设置，超时则抛出异常，生产者发送的消息如果指定了超出主题范围的分区，则会导致不断重试获取元数据信息直至超时。

消费者客户端的元数据更新也是通过 Sender 线程完成，生产者客户端在初始化时启动 Sender 线程，在线程的

客户端的元数据更新是在内部完成的，对外不可见。客户端需要更新元数据时，首先根据 InFlightRequests 获取负载最低的节点 leastLoadedNode(未确认请求最少的节点)，然后向这个节点发送 MetadataRequest 请求来获得元数据信息，更新操作是由 Sender 线程发起，在创建完 MetadataRequest 之后同样会存入 InFlightRequests。

```java

```