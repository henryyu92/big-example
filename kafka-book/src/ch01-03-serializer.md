# 序列化器

生产者客户端需要将消息的 key 和 value 序列化成二进制形式，Kafka 提供了 `Serializer` 接口定义的序列化器来完成序列化：
```java
byte[] serialize(String topic, T data);
```
Kafka 提供了常见的序列化方器，包括 `StringSerializer`, `ByteArraySerialzier` 等。序列化器需要在创建生产者客户端时指定：
```java
properties.put("key.serializer", "key_serializer_class_name");
properties.put("value.serializer", "value_serializer_class_name");
```
对于复杂的数据类型可以使用 `Thrif`、`ProtoBuf` 等工具实现序列化，通过实现 `Serializer` 接口可以自定义序列化方式。
```java

// todo ProtoBufSerializer
```