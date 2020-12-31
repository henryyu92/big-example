# 序列化器
序列化器负责将消息序列化成二进制形式，Kafka 提供了 `Serializer` 接口定义序列化器，通过实现接口可以自定义消息的序列化方式：
```java
byte[] serialize(String topic, T data);
```
Kafka 提供了常见的序列化方器，包括 `StringSerializer`, `ByteArraySerialzier` 等，通过实现 `Serializer` 接口也可以自定义序列化算法。Kafka 消息的 key 和 value 都需要序列化，序列化器需要在创建生产者客户端时指定：
```java
properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "key_serializer_class_name");
properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "value_serializer_class_name");
```
