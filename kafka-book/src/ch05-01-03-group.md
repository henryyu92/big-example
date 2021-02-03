# 消费组管理
Kafka 通过 `kafka-consumer-groups.sh` 脚本提供了对消费组及其消费位移的管理。

## 查看消费组

在 Kafka 中可以通过 ```kafka-consumer-groups.sh``` 脚本查看或变更消费组信息，通过 list 指令列出当前集群中所有的消费组：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```
通过 describe 指令可以查看指定消费组的详细信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \ 
--describe --group groupIdMonitor
```
其中 TOPIC 表示消费组订阅的主题，PARTITION 表示主题对应的分区号，CURRENT-OFFSET 表示消费组最新提交的消费位移，LOG-END-OFFSET 表示的是 HW，LAG 表示消息滞后的数量，CUNSUMER_ID 表示消费组的成员 ID，HOST 表示消费者 host，CLIENT_ID 表示消费者 clientId

消费组一共有 Dead、Empty、PreparingRebalance、Stable 这几种状态，正常情况下一个具有消费者成员的消费组的状态为 Stable，可以使用 state 参数查看消费组状态：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --state
```
如果消费组内没有消费者则消费组为 Empty 状态，可以通过 members 参数列出消费组内的消费者成员信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members
```
使用 verbose 参数可以查看每个消费者成员的分配情况：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members --verbose
```
使用 delete 指令删除指定的消费组，如果消费组中有消费者正在运行则会删除失败：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--delete --group groupIdMonitor
```

## 消费位移管理
```kafka-consumer-groups.sh``` 脚本提供了通过 reset-offsets 指令来重置消费组内的消费位移，前提是该消费组内没有消费者运行：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--group groupIdMonitor --all-topics --reset-offsets --to-earliest
```