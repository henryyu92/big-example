# 客户端
`KafkaProducer` 对象表示生产者客户端，在创建实例的时候需要指定必要的参数：
- `bootstrap.servers`：broker 地址列表，具体格式为 `host1:prot1,host2:port2`，这里不需要配置所有 broker 的地址因为生产者会从给定的 broker 里查找到其他 broker 的信息
- `key.serializer`：消息的 `key` 的序列化类，消息的 `key` 用于计算消息所属的分区
- `value.serializer`：消息的 `value` 的序列化类，`value` 是实际需要发送的消息，客户端在将消息发送到 broker 之前需要对消息进行序列化处理

`KafkaProducer` 是线程安全的，因此可以以单例的形式创建，也可以将 `KafkaProducer` 实例进行池化以在高并发的情况下提升系统的吞吐:
```java
// todo KafkaProducerFactory
```
