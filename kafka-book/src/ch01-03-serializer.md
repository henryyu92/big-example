## 序列化器

生产者客户端需要将消息的 key 和 value 序列化成二进制形式，Kafka 提供了 `Serializer` 接口定义的序列化器来完成序列化
：
```java
byte[] serialize(String topic, T data);
```
实现 `Serialize` 接口可以定义序列化器，Kafka 提供了常见的序列化方器，包括 `StringSerializer`, `ByteArraySerialzier` 等，序列化器需要在创建生产者客户端时指定：
```java
properties.put(
    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "key_serializer_class_name");
properties.put(
    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "value_serializer_class_name");
```
Kafka 提供了常见类型的序列化器，对于复杂的类型可以使用 `Thrif`、`ProtoBuf` 等工具实现：
```java

// todo ProtoBufSerializer
```