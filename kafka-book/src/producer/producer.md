## 生产者

生产者是将消息发送到 Kafka 集群的客户端，消息在生产者客户端经过拦截器、分区器以及序列化器等作用后缓存到 `RecordAccumulator`，然后批量的发送到消息分区对应的 `Broker`。

生产者在发送消息前会从元数据信息中获取分区对应的 Broker，Kafka 生产者维护了集群的元数据信息，并且在集群元数据信息发生变化时及时的更新。

