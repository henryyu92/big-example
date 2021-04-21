## 元数据

Kafka 元数据指的是集群的信息，包括主题、分区、节点等信息，生产者客户端发送消息时根据元数据信息将消息发送到分区对应的 Broker 上。

Kafka 主题信息和分区信息由 `TopicPartition` 以及 `PartitionInfo` 表示，由 `MetadataCache` 维护的实体类 `Cluster` 保存。
```java
// 集群信息
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

// 主题分区
public final class TopicPartition {
    // 分区
    private final int partition;
    // 主题
    private final String topic;
}

// 分区信息
public class PartitionInfo{
    // 主题
    private final String topic;
    // 分区号
    private final int partition;
    // 分区 leader 副本
    private final Node leader; 
    // 分区所有副本(AR)
    private final Node[] replicas;
    // 分区 ISR
    private final Node[] inSynReplicas;
    // 分区 OSR
    private final Node[] offlineReplicas;

}
```
Kafka 元数据的所有操作由 `Metadata` 管理，其内部维护了多个状态变量用于控制元数据的更新。`Metadata` 作为 `KafkaProducer` 的变量在其创建的时候实例化，`Metadata` 在 `KafkaProducer` 中会被多个线程读取，而其更新由 `Sender` 线程完成，为了保证线程安全，其对外暴露的所有方法都是 `synchronized`。
```java
public class Metadata extends Closeable {
    // 更新失败时，backoff 时间
    private final long refreshBackoffMs;
    // metadata 失效时间，失效后会强制更新
    private final long metadataExpireMs;
    // 更新版本，每次更新 +1
    private int updateVersion;
    private int requestVersion;
    // 上次更新时间
    private long lastRefreshMs;
    // 上次更新成功时间
    private long lastSuccessfulRefreshMs;
    
    // ...
}
```

### `Metadata` 更新

`KafkaProducer` 在每次发送消息时都需要调用 `waitOnMetadata` 方法来获取集群的元数据信息，如果元数据不存在则会阻塞的等待元数据更新直到超时。
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
Kafka 生产者客户端在发送消息时如果元数据没有 topic 以及 partition 的信息则会阻塞的更新元数据信息，，生产者发送的消息如果指定了超出主题范围的分区，则会导致不断重试获取元数据信息直至超时。

获取元数据的整个过程时长由客户端参数 `metadata.max.age.ms`(默认 5m)设置，超时则抛出异常

消费者客户端的元数据更新也是通过 Sender 线程完成，生产者客户端在初始化时启动 Sender 线程，在线程的

客户端的元数据更新是在内部完成的，对外不可见。客户端需要更新元数据时，首先根据 InFlightRequests 获取负载最低的节点 leastLoadedNode(未确认请求最少的节点)，然后向这个节点发送 MetadataRequest 请求来获得元数据信息，更新操作是由 Sender 线程发起，在创建完 MetadataRequest 之后同样会存入 InFlightRequests。

```java

```

### `Metadata` 失效


## 参考
- https://blog.csdn.net/chunlongyu/article/details/52622422
- https://blog.csdn.net/a1240466196/article/details/111350353