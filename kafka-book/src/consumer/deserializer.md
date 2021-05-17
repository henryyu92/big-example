## 反序列化
消费者客户端从集群拉取到消息之后需要对消息的 key 和 value 反序列化，反序列化方式需要和生产者客户端的序列化方式对应。Kafka 提供了 `Deserializer` 接口定义反序列化器，通过实现接口可以自定义消息的反序列化方式：
```java
// 将消息反序列化为特定对象
T deserialize(String topic, byte[] data);
```
Kafka 内置了 `StringDeserializer`、`ByteBufferDeserializer` 等多种反序列化器，反序列化器需要在创建消费者客户端时设置：
```java
props.setProperty("key.deserializer", "key.deserializer.class");
props.setProperty("value.deserializer", "value.deserializer.class");
```