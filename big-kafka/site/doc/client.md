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

生产者将业务数据包装成 `ProducerRecord` 作为消息，但其包含了多个消息相关的属性：

```java
public class ProducerRecord {
    
    private final String topic;
    private final Integer partition;
    private final Headers headers;
    private final K key;
    private final V value;
    private final Long timestamp;
}
```



#### 参数调优

### 消费者

#### 反序列化

### Admin