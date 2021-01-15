# OffsetManager


## Offset



## `__consumer_offset`

Kafka 将消费者提交的 offset 持久化到内部主题 ```__consumer_offsets``` 中，消费者在向 broker 拉取数据时，broker 在 ```__consumer_offsets``` 中获取拉取的起始消息位置。


位移提交的内容最终会保存到 Kafka 的内部主题 __consumer_offsets 中。一般情况下，当集群中第一次有消费者消费消息时会自动创建主题 __consumer_offsets，副本因子可以通过 ```offsets.topic.replication.factor``` 参数设置，分区数可以通过 ```offsets.topic.num.partitions``` 参数设置

客户端提交消费位移是使用 OffsetConmmitRequest 请求实现的，OffsetCommitRequest 的结构如下：
- group_id
- generation_id
- member_id
- retention_time 表示当前提交的消费位移所能保留的时长，通过 ```offsets.retention.minutes``` 设置
- topics

最终提交的消费位移会以消息的形式发送到主题 __consumer_offsets，与消费位移对应的消息也只定义了 key 和 value 字段的具体内容，它不依赖于具体版本的消息格式，以此做到与具体的消息格式无关。

在处理完消费位移之后，Kafka 返回 OffsetCommitResponse 给客户端，OffsetCommitResponse 的结构如下：
```java
```
可以通过 ```kafka-console-consumer.sh``` 脚本来查看 __consumer_offsets 中的内容：
```shell
```
如果有若个案消费者消费了某个主题的消息，并且也提交了相应的消费位移，那么在删除这个主题之后会一并将这些消费位移信息删除。