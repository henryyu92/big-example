### 主题管理
主题管理包括创建、查看、修改、删除主题等操作，Kafka 的主题操作通过 Kafka 提供的 kafka-topics.sh 脚本执行，该脚本位于 $KAFKA_HOME/bin 目录下，其本质是调用 ```kafka.admin.TopicCommand``` 类来执行主题的管理操作。
#### 创建主题
如果 broker 配置参数 ```auto.create.topics.enable``` 设置为 true(默认)，那么当生产者向一个尚未创建的主题发送消息时会自动创建一个分区数为 ```num.partitions``` (默认为 1)、副本因子为 ```default.replication.facotr``` (默认为 1) 的主题，另外当消费者开始从未知主题中读取消息时或者任意一个客户端向未知主题发送元数据请求时都会按照配置参数的值创建一个相应的主题。不建议将 ```auto.create.topics.enable``` 设置为 true，因为这会增加主题管理与维护的难度。

使用 ```kafka-topics.sh``` 脚本来创建主题是更通用的方式：
```shell
# --zookeeper 指定 ZooKeeper 连接地址
# --create 表示创建主题
# --topic 指定创建的主题名称
# --partitions 指定主题的分区数
# --replication-facotr 指定分区的副本因子，副本的数量不能多于 broker 的数量

bin/kafka-topics.sh \
--zookeeper localhost:2181 \
--create \
--topic topic-create \
--partitions 4 \
--replication-factor 2 \
```
执行完脚本之后，Kafka 会在broker 节点的 log.dir 或 log.dirs 参数所配置的目录(默认 /tmp/kafka-logs/)下创建相应的主题分区目录，目录名为 ```<topic>-<partitionNum>```。当创建一个主题时会在 ZooKeeper 的 /brokers/topics/ 目录下建立一个和主题同名的 ZNode，该节点记录了主题的分区副本和 broker 对应的分配方案。
```shell
get /brokers/topics/topic-create

# "2":[1,2] 表示分区号为 2 的副本分布在 brokerId 为 1 和 2 的 broker 上
{"version":"1", "partitions":{"2":[1,2], "1":[0,1],"3":[2,1],"0":[2,0]}}
```
还可以通过 ```kafka-topics.sh``` 脚本的 describe 指令来查看分区副本的分配细节：
```shell
bin/kafka-topics.sh \
--zookeeper localhost:2181 \
--describe \
--topic topic-create \

# Topic 表示创建的主题，PartitionCount 表示分区数，RepilicationFactor 表示副本数，Configs 表示主题配置
Topic:topic-create	PartitionCount:4	RepilicationFactor:2	Configs:
# Topic 表示创建的主题
# Partition 表示分区 id
# Leader 表示 leader 副本对应的 brokerId
# Replicas 表示分区所有副本对应的 brokerId
# Isr 表示分区副本的 ISR 集合对应的 brokerId
Topic:topic-create	Partition: 0	Leader: 2	Replicas: 2,0	Isr: 2,0
Topic:topic-create  Partition: 1	Leader: 0	Replicas: 0,1	Isr: 0,1
```
```kafka-topics.sh``` 脚本还提供了一个 replica-assignment 参数来手动指定分区副本的分配方案，分区从小到大排列，分区与分区之间使用逗号(,)隔开，分区内的副本用冒号(:)隔开，同一个分区的副本不能有重复，分区之间的副本数必须相同：
```shell
# 分区分配方案为：
# 0 分区副本对应的 brokerId 为 2,0；
# 1 分区副本对应的 brokerId 为 0,1；
# 2 分区部分对应的 brokerId 为 1,2；
# 3 分区副本对应的 brokerId 为 2,1
bin/kafka-topics.sh --zookeeper localhost:2181 \
--create \
--topic topic-create-same \
--replica-assignment 2:0,0:1,1:2,2:1
```
创建主题的时候还可以使用 config 参数可以设置要创建的主题的相关参数，可以覆盖原本的默认值：
```shell
bin/kafka-topics.sh \
--zookeeper localhost:2181 \
--create \
--topic topic-config \
--replication-factor 1 \
--partitions 1 \
--config cleanup.policy=compact \
--config max.message.bytes=10000
```
Kafka 不允许创建同名的主题，```kafka-topics.sh``` 提供了 if-not-exists 参数在主题名发生冲突时不做任何处理来避免在创建主题时由于主题名冲突而抛出异常：
```shell
bin/kafka-topics.sh \
--zookeeper localhost:2181 \
--create \
--topic topic-create \
--partitions 4 \
--replication-factor 2 \
--if-not-exists
```
创建一个主题时无论是通过 ```kafka-topics.sh``` 还是通过其他方式本质上是在 ZooKeeper 中的 /brokers/topics 节点下创建与主题对应的子节点并写入分区副本分配方案，并且在 /config/topics/ 节点下创建与该主题对应的子节点并写入相关的配置信息，因此可以直接使用 ZooKeeper 的客户端在 /broker/topics 节点下创建主节点并写入副本分配方案，这样可以绕过 ```kafka-topics.sh``` 创建主题时的一些限制：
```shell
# 创建主题及其分区分配方案
create /brokers/topics/topic-create-zk {"version":1,"partitions":{"2":[1,2],"1":[0,1],"3":[2,1],"0":[2,0]}}
# 指定主题参数
create /config/topics/topic-create-zk {"version":1,"config":{"cleanup.policy":"compact","max.message.bytes":"10000"}}
```
#### 查看主题
```kafka-topics.sh``` 脚本的 list 指令可以查看 Kafka 的所有主题，describe 指令可以查看一个或多个 topic 的信息，只需要指定多个需要查看的 topic 即可：
```shell
bin/kafka-topics.sh --zookeeper localhost:2181 --list

bin/kafka-topics.sh --zookeeper localhost:2181 --describe --topic topic1,topic2
```
describe 指令还提供了额外的参数增加附加功能：
- ```topics-with-overrides``` 参数可以找出所有包含覆盖配置的主题，列出包含了与集群默认配置不一样的主题；
- ```under-replicated-partitions``` 参数可以找出所有包含失效副本的分区，此时 ISR 小于 AR，但是失效的副本可能正在进行同步；
- ```unavailable-partitions``` 参数可以查看主题中没有 leader 副本的分区，这些分区已经处于离线状态，对于外界的生产者和消费者来说是不可用状态：
```shell
bin/kafka-topics.sh --zookeeper localhost:2181 --describe --topics-with-overrides

bin/kafka-topics.sh --zookeeper localhost:2181 --describe --under-replicated-partitions

bin/kafka-topics.sh --zookeeper localhost:2181 --describe --unavailable-partitions
```
#### 修改主题
```kafka-topics.sh``` 脚本的 alter 指令可以修改已经创建的主题，Kafka 只支持增加分区而不支持减少分区，原因在于如果减少分区则减少的分区数据处理将会变得非常麻烦。当修改一个不存在的 topic 时，使用 --if-exists 参数来忽略修改：
```shell
bin/kafka-topics --zookeeper localhost:2181 \
--alter \
--topic topic-config \
# 修改分区数
--partitions 3 \
--if-exists
```
主题的修改特别是分区的修改会使得原有的数据受到很大的影响，如 producer 端根据 key 计算分区，消息的有序性、事务等变得很难保证，因此一般不建议修改分区。
#### 删除主题
```kafka-topics.sh``` 脚本的 delete 指令可以用于删除主题，必须配置 ```delete.topic.enable``` 参数为 true 才能删除 topic，不能删除 Kafak 内部主题和不存在的主题：
```shell
bin/kafka-topics --zookeeper localhost:2181 --delete --topic topic-delete
```
删除主题的操作本质是在 ZooKeeper 的 /admin/delete_topics 下创建一个与主题同名的节点标记该主题需要被删除，而真正的删除操作由 Kafka 的控制器完成。
```shell
create /admin/delete_topics/topic_delete ""
```
创建主题时得知其元数据存储在 ZooKeeper 的 /brokers/topics 和 /config/topics 路径下，消息数据存储在 log.dir 或 log.dirs 配置的路径下，因此也可以手动删除主题：
```shell
rmr /brokers/topics/topic_delete
rmr /config/topics/topic_delete

rm -rf /<log.dir>/topic_delete
```
删除主题操作是不可逆的，一旦删除之后对应的消息数据也会全部删除且不可恢复。
#### 主题参数
主题的参数基本上在 broker 上都有对应的配置，如果创建主题时没有指定参数则使用 broker 对应参数的默认值。

|topic 参数|broker 参数|默认值|含义|
|-|-|-|-|
|cleanup.policy|log.cleanup.policy|delte|日志压缩策略，可选 delete 和 compact|
|compression.type|compression.type|producer|消息压缩类型，可选 producer,uncompressed,snappy,lz4,gzip|
|delete.retention.ms|log.cleaner.delete.retention.ms|86400000(1天)|被标识为删除的数据保留的时间|
|file.delete.delay.ms|log.segment.delete.delay.ms|60000(1 分钟)|清理文件之前等待的时间|
|flush.message||||
|flush.ms||||
|follower.replication.throttled.replicas||||
|index.interval.bytes||||
|leader.replication.throttled.replicas||||
|max.message.bytes||||
|message.format.version||||
|message.timestamp.difference.max.ms||||
|message.timestamp.type||||
|min.cleanable.dirty.ratio||||
|min.compaction.lag.ms||||
|min.insync.replicas||||
|preallocate||||

### 配置管理
```kafka-configs.sh``` 脚本是专门用来对配置进行操作的，也就是在运行状态下修改原有的配置达到动态变更的目的。脚本包含变更配置 alter 和查看配置 describe 这两种指令类型，支持主题、broker、用户和客户端的配置。

```kafka-configs.sh``` 使用 --entity-type 参数指定操作配置类型，使用 --entity-name 参数指定配置配置的名称，对应关系为：

|entity-type|entity-name|
|-|-|
|topics|主题名|
|brokers|brokerId|
|clients|clientId|
|users|用户名|
```shell
bin/kafka-configs.sh --zookeeper localhost:2181 \
--describe
--entity-type topics
--entity-name topic-config
```
alter 指令使用 --add-config 和 --delete-config 两个参数实现配置的增、改和删，多个配置参数之间用逗号(，)隔开：
```shell
bin/kafka-configs.sh --zookeeper localhost:2181 \
--alter \
--entity-type topics \
--entity-name topic-config \
--add-config clelanup.policy=compact,max.message.bytes=10000 \
--delete-config cleanup.policy 
```
使用 ```kafka-configs.sh``` 脚本来变更配置是在 ZooKeeper 中创建一个命名形式为 ```/config/<entity-type>/<entity-name>``` 的节点并将变更的配置写入这个节点，同时还会在 ZooKeeper 创建 ```/config/changes/config_change_<seqNo>``` 的持久顺序节点表示节点配置的变更。
```shell
get /config/topics/topic-config

ls /config/changes
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
### 消费组管理
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