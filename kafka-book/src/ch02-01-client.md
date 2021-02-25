# 客户端
`KafkaConsumer` 表示 Kafka 消费者客户端，在创建实例的时候需要指定集群的地址，消息的 key 和 value 的反序列化方式，以及消费者所属的消费组。
```java
// Broker 集群地址，不需要指定所有机器
properties.put("bootstrap.servers", "host:port");
// 消费组 id
properties.put("group.id", "group-id");
// key 反序列化器，需要和生产者序列化器对应
properties.put("key.deserializer", "key.deserializer.class.name");
// value 反序列化器，需要和生产者序列化器对应
properties.put("value.deserializer", "value.deserializer.class.name");
```
消费者客户端在消费消息后需要向集群提交消费位移，在多线程情况下不能保证消费位移的正确提交，因此消费者客户端不是线程安全的。

## 主题订阅
Kafka 采用发布/订阅模型，即消费者在拉取消息之前需要订阅主题。`KafkaConsumer` 提供了三种重载的订阅主题的方法，不同的订阅方法不能混合使用，否则会抛出 `IllegalStateException` 异常：
```java
// 以集合的方式订阅主题
subscribe(Collection<String> topics, ConsumerRebalanceListener listener)

// 以正则表达式的方式订阅主题
subscribe(Pattern pattern, ConsumerRebalanceListener listener)

// 订阅主题的特定分区
assign(Collection<TopicPartition> partitions)
```
在订阅指定分区的时候，可以使用 `KafkaConsumer#partitionsFor(topic)` 方法获取元数据信息，其中包含了主题的分区信息。
```java
// 主题的分区信息
List<PartitionInfo> prititionInfos = consumer.partitionsFor(topic);
if(partitionInfos != null && partitionInfos.size() > 1){
    consumer.assign(Collections.singleton(new TopicPartition(topic, partitionInfos.get(0).partition)))
}
```
`KafkaConsumer` 订阅主题的方法需要传入回调参数 `ConsumerRebalanceListener`，在消费组中的消费者发生变化或者主题的分区数发生变化时，Kafka 会根据分区分配策略为每个订阅了主题的消费者重新分配消费分区，并且在消费分区发生变化时通过回调监听器来通知消费者客户端。`ConsumerRebalanceListener` 接口定义了两个方法：
```java
// 消费者停止拉取消息之后调用，通常会提交或者存储 offset 来避免重复消费
void onPartitionsRevoked(Collection<TopicPartition> partitions);

// 再均衡后消费者开始拉取消息前调用，通常会重新定位拉取消息的 offset
void onPartitionsAssigned(Collection<TopicPartition> partitions);
```
订阅了主题的消费者客户端可以通过 `KafkaConsumer#unsbuscribe()` 方法取消订阅，取消订阅后消费者拉取消息会抛出异常。取消订阅主题后消费者客户端的数量发生变化，会触发消费组内的消费者重新分配分区。

## 消息消费

消费者客户端订阅主题之后就可以从集群获取消息消费，消息的消费模式一般有两种：推模式和拉模式，推模式是服务端主动将消息推送给消费者，拉模式则是消费者主动向服务端发起请求拉取消息。

Kafka 消费者客户端的消息消费是基于拉模式的，消费者不断轮询调用 poll 方法从服务端拉取订阅的消息，如果分区中没有消息就返回空消息集合。
```java
// 等待 timeout 后如果没有消息则返回空集合
public ConsumerRecords<K, V> poll(final Duration timeout)
```
`KafkaConsumer` 拉取的消息是 `ConsumerRecords` 表示的集合，其中包含每个主题的消息列表，当主题没有新的消息时，对应的消息列表为空。消息列表中的对象是 `ConsumerRecord` 表示 Kafka 消费者客户端真正消费的消息：
```java
// 分区-消息集合
public class ConsumerRecords{
    private final Map<TopicPartition, List<ConsumerRecord<K, V>>> records;
}

// 消费者消息
public class ConsumerRecord<K, V>{
  // 消息的主题
  private final String topic;
  // 消息的分区
  private final int partition;
  // 消息在所属分区的偏移量
  private final long offset;
  // 消息的时间戳
  private final long timestamp;
  // 消息的时间戳类型，CreateTime 或 LogAppendTime
  private final TimestampType timestampType;
  // 序列化后 key 的大小
  private final int serializedKeySize;
  // 序列化后 value 的大小
  private final int serializedValueSize;
  // 消息的 header
  private final Headers headers;
  // 消息的 key
  private final K key;
  // 消息的 value
  private final V value;
  private final Optional<Integer> leaderEpoch;
  // 消息的 CRC32 校验值
  private volatile Long checksum;

}
```
`KafkaConsumer` 提供了对消息消费的控制，`pause(partitions)` 和 `resume(partitions)` 方法可以控制暂停和回复拉取指定分区的消息，通过 `paused()` 方法可以查看暂停拉取消息的分区。

## 并发消费
`KafkaConsumer` 是非线程安全的，在对外的方法中会通过 `aquire()` 方法检测时候有多个线程操作同一个 `KafkaConsumer` 对象，如果发现有其他线程正在操作则会抛出 `ConcurrentModificationException`。

消费者客户端和下游业务绑定，如果下游业务负载较重则会影响整个 Kafka 的吞吐量，此外堆积在 Broker 中的消息也有可能因为日志清理机制被清理从而导致消息丢失。

Kafka 消费者客户端不允许多个线程操作，但是借助于 Kafka 消费组，我们直接在相同的消费组中增加订阅相同主题的消费者客户端即可线性的增加消费者的吞吐量(消费者客户端数量不超过分区数量)。
```java
public class ConsumerThread<K,V> implements Runnable {
  
  private final KafkaConsumer<K, V> consumer;

  public ConsumerThread(String broker, String group, Collection<String> topics, K keyDeserializer, V valueDeserializer){
    Properties properties = new Properties();
    properties.setProperty("bootstrap.servers", broker);
    properties.setProperty("group.id", group);
    properties.setProperty("key.deserializer", keyDeserializer.getClass().getName());
    properties.setProperty("value.deserializer", valueDeserializer.getClass().getName());

    consumer = new KafkaConsumer<K, V>(properties);
    consumer.subscribe(topics);
  }
 
    @Override
    public void run() {
      // ...
    }
}
```
创建多个消费者可以提高消费者客户端的吞吐量，但是线程之间的频繁切换会消耗较多的系统资源。通过将 Kafka 消费者客户端与下游业务处理解耦是提升吞吐量的另外一种方式，此时消费者客户端作为 I/O 线程负责拉取消息和提交消费位移，而下游处理程序则负责消息的处理。

使用消费者客户端线程拉取消息并记录分区的消费位移，在业务端通过线程池的方式处理消费者客户端拉取的消息，当业务消费失败时可以通过消费者客户端的 `seek()` 方法重新定位到上次消费的地方从而避免消息丢失。
```java
public static class RecordHandler implements Runnable {

  public final ConsumerRecords<String, String> records;

  public RecordHandler(ConsumerRecords<String, String> records){
    this.records = records;
  }

  @Override
  public void run() {
    // process
  }
}
```
消息采用线程池的方式处理，因此不能保证消息消费的顺序性，另外由于消息处理的速率不同可能会导致在提交消费位移时有些消息尚未消费成功，可以借用窗口的思想，每次提交消费位移时保证小于当前 offset 的消息都已经消费。

## 脚本工具

```shell script
# --bootstrap-server    集群地址
# --group-id            消费组
# --topic               订阅主题
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
--group-id  group-hello \
--topic topic-hello
```