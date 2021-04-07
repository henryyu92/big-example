# 分区器

消息在发送到 `Broker` 之前需要确定消息的分区，客户端在创建消息 `ProducerRecord` 时如果指定了 `partition` 则消息会被发送到 `partition` 对应的 `Broker`，否则需要根据消息的 `key` 进行计算消息的分区。

Kafka 使用 `Partitioner` 接口定义的分区器计算消息的分区，通过实现分区器接口可以自定义消息的分区算法。
```java
/**
 * 计算消息的分区
 *
 * topic        消息的主题
 * key          消息的 key，没有指定则为 null
 * keyBytes     key 的字节数组，没有指定 key 则为 null
 * value        消息的值
 * valueBytes   消息值的序列化数组，没有值则为 null
 * cluster      集群的元数据信息
 */
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster);
```
Kafka 默认使用 `DefaultPartitioner` 分区器的分区算法，需要显式指定分区器则需要在创建生产者客户端时显式的设置：
```java
properties.put("partitioner.class", "partitioner_class_name");
```
## `DefaultPartitioner`

`DefaultPartitioner` 是 Kafka 默认的分区器，在消息的 key 为空时使用 `StickyPartitioner` 来计算分区，在消息的 key 不为空时则直接将 key 哈希后对消息所属的主题的分区数取模得到当前消息的分区。
```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    if (keyBytes == null) {
        return stickyPartitionCache.partition(topic, cluster);
    } 
    List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
    int numPartitions = partitions.size();
    // hash the keyBytes to choose a partition
    return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
}
```
在消息所属的主题的分区数不发生变化的情况下，`DefaultPartition` 保证具有相同的 key 的消息计算到同一个分区，但是主题的分区数如果发生变化，则就无法保证这种对应关系。

## `RoundRobinPartitioner`
`RoundRobinPartitioner` 为消息对应的 `topic` 维护了一个计数器，如果消息所属主题的**可用分区**集合不为空则通过将计数器对可用分区数取模得到消息的分区；否则将计数器对**所有分区**取模得到消息的分区。
```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    // 所有分区
    List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
    int numPartitions = partitions.size();
    int nextValue = nextValue(topic);
    // 可用分区
    List<PartitionInfo> availablePartitions = cluster.availablePartitionsForTopic(topic);
    if (!availablePartitions.isEmpty()) {
        int part = Utils.toPositive(nextValue) % availablePartitions.size();
        return availablePartitions.get(part).partition();
    } else {
        // no partitions are available, give a non-available partition
        return Utils.toPositive(nextValue) % numPartitions;
    }
}
```

## `UniformStickyPartitioner`
`UniformStickyPartitioner` 缓存了主题上个消息的分区，
```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    return stickyPartitionCache.partition(topic, cluster);
}
```