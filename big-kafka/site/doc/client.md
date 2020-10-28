### 生产者

生产者通常是业务程序，通过 Kafka 提供的生产者客户端可以向 Kafka 集群发送消息。Kafka 提供了 `KafkaProducer` 作为生产者客户端实例，在创建时需要配置必须的属性：

- `broker.servers`：指定客户端连接的 Kafka 集群，格式为 `host:port,...`
- `key.serializer`：指定 key 的序列化方式，Kafka 是以字节的形式存储数据的，因此发送到 Broker 的数据需要序列化为字节数组
- `value.serializer`：指定 value 的序列化方式

```java
public <K, V> KafkaProducer<K, V> newProducer(
    String brokers, Class<K> keySerializer, Class<V> valueSerializer) {
    
    Map<String, String> configMap = new HashMap(){{
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer.getName());
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer.getName());
    }};
    
  	return new KafkaProducer<>(configMap);
}
```



`KafkaProducer` 是线程安全的，可以在多个线程中贡献同一个实例，通过池化技术可以对 `KafkaProducer` 进行集中的管理从而减少创建和销毁的开销。

```java

```

#### 拦截器

#### 序列化

#### 分区器

#### 消息发送

生产者将业务数据包装成 `ProducerRecord` 作为消息，其包含了多个消息相关的属性：

```java
public class ProducerRecord {
    
    // 消息主题
    private final String topic;
    // 消息分区
    private final Integer partition;
    // 消息头
    private final Headers headers;
    // 消息的 key，用于计算消息分区
    private final K key;
    // 消息
    private final V value;
    // 消息创建时间或者追加到日志的时间
    private final Long timestamp;
}
```

`ProducerRecord` 定义了客户端向 Broker 发送的消息的格式，其中除了 `topic` 必需外其他都是可选的。`partition` 字段表示消息发送的分区，如果未指定则由 Kafka 提供的算法计算，`key` 字段用于计算消息发送的分区。`timestamp` 字段在不同的配置下有不同的涵义，如果消息所属的主题设置为 `CREATE_TIME` 则表示生产者创建消息的时间戳，如果设置为 `LOG_APPEND_TIME` 则表示 Broker 将消息追加到日志的时间戳。

Kafka 生产者客户端提供了两种异步发送消息方式，返回的 `Future` 对象包含了消息发送的结果：

```java
// 等价于 send(record, null)
Future<RecordMetadata> send(ProducerRecord<K, V> record);

Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback);
```

消息异步发送成功后会调用设置的回调函数，`send` 方法在调用之后会立即返回而消息会被缓存到 `RecordAccumulator` 中直到某个条件下才会发往 Broker。

`send` 方法返回的对象 `RecordMetadata `表示消息发送的结果，其包含了消息存储的分区、偏移量等重要的信息：

```java
public final class RecordMetadata {
    // 消息在分区的 offset
    private final long offset;
    // 消息的时间戳，可以为创建时间或者追加到日志的时间
    private final long timestamp;
    private final int serializedKeySize;
    private final int serializedValueSize;
    // 消息所属分区信息
    private final TopicPartition topicPartition;
}
```

Kafka 消息发送采用异步的方式，在发送的过程中可能会发生两类异常：可重试异常和不可重试异常。对于可重试异常一般是由于网络问题导致消息发送失败(如 `NetWorkException`) 或者集群暂时不可用(如 `LeaderNotAvailableException`)，这些异常一般可以通过重试解决；对于不可重试异常一般是由于消息异常导致 Broker 拒绝接收(如 `RecordTooLargeException`)，此时一般需要调整参数来解决。

Kafka 在创建客户端实例时可以设置消息发送重试次数的参数 `retries`，默认值为 0。设置重试参数后在发生可重试异常时就会自动重试，如果重试之后仍然异常则回调函数的 `onCompletion` 方法中会传入异常参数,，此时需要手动处理异常：

```java
public class LogErrorHandler implements Callback {
    
    private Logger logger = Logger.getLogger(LogErrorHandler.class.getName());
    
    @Override
    public void onCompletion(RecordMetadata metadata, Exception exception) {
        if (exception != null){
        }
    }
}
```



#### 流程分析

#### 参数调优

### 消费者

#### 反序列化

### Admin