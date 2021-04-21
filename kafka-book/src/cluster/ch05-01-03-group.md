## 消费组管理
Kafka 通过 `kafka-consumer-groups.sh` 脚本提供了对消费组及其消费位移的管理。

### 查看消费组

使用 Kafka 提供的 `kafka-consumer-groups.sh` 脚本的 `--list` 选项可以查看当前集群中的所有消费组:
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```
通过 `--describe` 指令可以查看指定消费组的详细信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \ 
--describe \
--group groupIdMonitor \
```
查看消费组信息时还可以通过 `--state` 选项查看消费组的状态，消费组可能会处于 `dead`, `empty`, `preparingRebalance`, `stable` 等几种状态。
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --state
```
`--member` 选项则可以查看消费组内的消费者信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members
```
`--verbose` 选项则可以查看每个消费者成员的分配情况：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members --verbose
```

### 删除消费组
`kafka-consumer-groups.sh` 脚本提供了 `--delete` 指定用于删除消费组，在删除的过程中如果有消费者正在消费则删除会失败：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--delete --group groupIdMonitor
```

### 重置消费位移

```kafka-consumer-groups.sh``` 脚本提供了 `reset-offsets` 指令来重置消费组内的消费位移，前提是该消费组内没有消费者运行：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--group groupIdMonitor --all-topics --reset-offsets --to-earliest
```

### 删除消息

当分区创建的时候起始位置(logStartOffset)为0，可以使用 ```KafkaConsumer#beginningOffsets``` 方法查看分区的起始位置。使用 ```kafka-delete-records.sh``` 脚本来删除部分消息，在执行消息删除之前需要配置执行删除消息的分区及位置的配置文件：

```shell
delete.json
{
    "partitions"[
        {"topic":"topic-monitor","partition":0,"offset":10},
        {"topic":"topic-monitor","partition":1,"offset":11},
        {"topic":"topic-monitor","partition":2,"offset":12}
    ],
    "versions":1
}

bin/kafka-delete-records.sh --bootstrap-server localhost:9092 --offset-json-file delete.json
```

