## 客户端
`KafkaProducer` 表示 Kafka 的生产者客户端，在创建实例的时候需要指定集群地址以及消息的 key 和 value 的序列化方式。
```java
// 指定集群地址，多个以 , 分割，不需要指定所有的机器
properties.put("bootstrap.servers", "host1:port1,host2:port2");
// 消息 key 的序列化器
properties.put("key.serializer", "key_serializer_class_name");
// 消息 value 的序列化器
properties.put("value.serializer", "value_serializer_class_name");
```
`KafkaProducer` 是线程安全的，因此可以以单例的形式创建，也可以将 `KafkaProducer` 实例进行池化以在高并发的情况下提升系统的吞吐:
```java
// todo KafkaProducerFactory
```

### 发送消息
Kafka 客户端发送的消息是将需要发送的数据包装后的 `ProducerRecord` 对象，其包含了多个消息相关的属性：
```java
public class ProducerRecord<K, V> {
    // 消息的主题
    private final String topic;
    // 消息的分区
    private final Integer partition;
    // 消息头
    private final Headers headers;
    // 消息的 key
    private final K key;
    // 消息内容
    private final V value;
    // 消息创建时间，没有指定则使用当前时间
    private final Long timestamp;
	
    // ...
}
```
消息对象在创建的时候必须指定 `topic`，如果指定了 `partition` 则消息会发送到指定的分区，否则会根据序列化后的 `key` 通过分区器的算法计算消息发送的分区。

Kafka 生产者客户端以异步的方式发送消息，返回的 `Future` 对象包含了消息发送的结果。Kafka 提供了两种消息发送的重载，如果指定了 `callback`，Kafka 生产者客户端会在消息发送完成后调用:
```java
public Future<RecordMetadata> send(ProducerRecord<K, V> record);

public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback);
```

消息发送完成后返回的结果由 `RecordMetadata` 表示，其中包含了消息的分区、消息偏移量等元数据信息：
```java
public final class RecordMetadata {
    // 消息偏移量
    private final long offset;
    // 消息创建时间或者追加到日志的时间
    private final long timestamp;
    // key 序列化后的字节数
    private final int serializedKeySize;
    // value 序列化后的字节数
    private final ing serializedValueSize;
    // 消息的 Topic 和 Partition 信息
    private final TopicPartition topicPartition;
    // 消息的 CRC32 校验和
    private volatile Long checksum;
	
    // ...
}
```

生产者客户端在发送消息的过程中会产生两类异常：可重试异常和不可重试异常。对于可重试异常，如果在创建生产者客户端时配置 `retries(默认 0)` 参数则在发生异常时会自动重试，对于不可重试异常则会直接向上层抛出。Kafka 生产者客户端保证发送到同一个分区的消息是有序的，并且 callback 也是分区有序的:
```java
producer.send(record, (metadata, exception) -> {
    
    if (exception != null) {
        
        // handle exception
    } else {
        // handle result
    }
});
```

### 脚本工具

Kafka 提供了通过控制台发送消息的脚本工具，`${KAFKA_HOME}/bin` 目录下的 `kafka-console-producer.sh` 是 Kafka 提供的生产者脚本工具，可以向集群发送消息。

```shell script
# --bootstrap-server  集群地址
# --topic             主题
# --partition         分区数，默认为 1
# --replicas          副本数，默认为 3

bin/kafka-console-producer.sh \
--bootstrap-server <broker_addr> \
--topic <topic_name> \
--partition <par_cnt> \
--replicas <rep_cnt>
```