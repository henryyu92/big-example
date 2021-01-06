# 生产者

生产者是将消息发送到 Kafka 集群的客户端，消息在客户端经过拦截器、分区器以及序列化器等作用后缓存到 `RecordAccumulator`，然后批量的发送到分区对应的 `Broker`。

Kafka 集群的元数据信息是动态变化的，生产者通过定时拉取的方式维护了整个集群的元数据信息，在集群发生变化能够及时的更新元数据信息以便消息能够发送到正确的 Broker 上。
