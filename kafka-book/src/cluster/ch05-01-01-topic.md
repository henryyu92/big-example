## 主题管理

Kafka 提供了对集群中主题的管理，通过 `$KAFKA_HOME/bin/kafka-topics.sh` 脚本工具可以进行主题的创建、查看、修改、删除等操作。

### 创建主题

集群的配置参数 `auto.create.topics.enable` 默认为 true，也就是说生产者向不存在的主题发送消息或者消费者从不存在的主题拉取消息都会自动的创建对应的主题，为了避免创建未知的主题，通常会将参数设置为 false 禁止自动创建主题，而是通过脚本工具创建主题。

`kafka-topics.sh` 脚本工具提供 `--create` 指令用于创建主题，创建主题时需要指定集群地址，主题名等信息：
```shell
# --create 表示创建主题
# --bootstrap-server 指定 broker 的地址
# --topic 指定创建的主题名称
# --partitions 指定主题的分区数，默认为 1
# --replication-factor 指定分区的副本因子，副本的数量不能多于 broker 的数量，默认为 1

bin/kafka-topics.sh \
--create \
--bootstrap-server localhost:9092 \
--topic topic-create \
--partitions 4 \
--replication-factor 2
```
执行创建主题命令后 Kafka 会自动创建主题以及对应的分区和副本，并分配到不同的 Broker 上，集群配置参数 `log.dir` 指定的目录下创建 `<topic-partition>` 的目录，并在 ZooKeeper 的 `brokers/topics` 目录下创建创建和主题同名的 ZNode，该节点记录了主题的分区副本和 broker 对应的分配方案。
```shell
get /brokers/topics/topic-create

# "2":[1,2] 表示分区号为 2 的副本分布在 brokerId 为 1 和 2 的 broker 上

{"version":"1", "partitions":{"2":[1,2], "1":[0,1],"3":[2,1],"0":[2,0]}}
```
创建主题的时候还可以指定分区副本的分配，使用 `--replica-assignment` 选项指定具体的副本分配方案。
```shell
# 副本方案从 0 分区开始，分区之间使用 ， 分隔，broker 之间使用 ： 分隔
# 0 分区副本对应的 brokerId 为 2,0
# 1 分区副本对应的 brokerId 为 0,1
# 2 分区部分对应的 brokerId 为 1,2
# 3 分区副本对应的 brokerId 为 2,1

bin/kafka-topics.sh \
--create \
--bootstrap-server localhost:9092 \
--topic topic-create-replica-assignment \
--replica-assignment 2:0,0:1,1:2,2:1
```
Kafka 不允许创建同名的主题，在创建主题时使用 `--if-not-exists` 选项可以使得在存在同名的主题时不会做任何处理。
```shell
bin/kafka-topics.sh \
--create \
--if-not-exists \
--bootstrap-server localhost:9092 \
--topic topic-create-not-exists \
--partitions 4 \
--replication-factor 2
```
创建主题的时候还可以使用 `--config` 选项设置主题的相关参数，以覆盖集群的默认值。
```shell
bin/kafka-topics.sh \
--create \
--bootstrap-server localhost:9092 \
--topic topic-create-with-config \
--replication-factor 1 \
--partitions 1 \
--config cleanup.policy=compact \
--config max.message.bytes=10000
```
使用 `kafka-topic.sh` 脚本工具创建主题的本质是在 ZooKeeper 的 `brokers/topics` 节点下创建和主题同名的节点并写入分区的分配方案，然后在 `/config/topics` 节点下创建和主题同名的节点并写入主题创建时的配置参数，通过操作 ZooKeeper 可以直接对主题进行操作从而绕过 Kafka 对设置参数的校验。
```shell
# 创建主题及其分区分配方案
create /brokers/topics/topic-create-zk {"version":1,"partitions":{"2":[1,2],"1":[0,1],"3":[2,1],"0":[2,0]}}

# 指定主题参数
create /config/topics/topic-create-zk {"version":1,"config":{"cleanup.policy":"compact","max.message.bytes":"10000"}}
```
### 查看主题

`kafka-topics.sh` 脚本工具提供了两种查看主题的方式： `--list` 指令查看所有的主题，`--describe` 指令查看指定的主题。
```shell
bin/kafka-topics.sh --list --bootstrap-server localhost:9092 

bin/kafka-topics.sh --describe --bootstrap-server localhost:9092 --topic topic1,topic2
```
describe 指令还提供了额外的参数增加附加功能：
- `--topics-with-overrides` 选项列出覆盖主题默认参数的主题以及配置参数
- `--under-replicated-partitions` 选项列出所有包含失效副本的分区，此时失效的副本可能正在进行同步
- `--unavailable-partitions` 选项列出主题中没有 leader 副本的分区，这些分区已经处于离线状态，对于外界的生产者和消费者来说是不可用状态
```shell
bin/kafka-topics.sh --describe --topics-with-overrides --bootstrap-server localhost:9092 

bin/kafka-topics.sh --describe --under-replicated-partitions --bootstrap-server localhost:9092

bin/kafka-topics.sh --describe --unavailable-partitions --bootstrap-server localhost:9092
```
### 修改主题
`kafka-topics.sh` 脚本的 `--alter` 指令可以修改已经创建的主题，Kafka 只支持增加分区而不支持减少分区，当修改一个不存在的 topic 时，使用 `--if-exists` 选项来忽略修改：
```shell
bin/kafka-topics \
--alter \
--if-exists \
--bootstrap-server localhost:9092 
--topic topic-modify \
# 修改分区数
--partitions 3
```
主题的修改特别是分区的修改会使得原有的数据受到很大的影响，如 producer 端根据 key 计算分区，消息的有序性、事务等变得很难保证，因此一般不建议修改分区。
### 删除主题
```kafka-topics.sh``` 脚本的 `--delete` 指令可以用于删除主题，必须配置 ```delete.topic.enable``` 参数为 true 才能删除 topic，不能删除 Kafka 内部主题和不存在的主题：
```shell
bin/kafka-topics \
--delete \
--bootstrap-server localhost:9092 \
 --topic topic-delete
```
删除主题的操作本质是在 ZooKeeper 的 `/admin/delete_topics` 下创建一个与主题同名的节点标记该主题需要被删除，而真正的删除操作由 Kafka 的控制器完成。
```shell
create /admin/delete_topics/topic_delete
```
创建主题时得知其元数据存储在 ZooKeeper 的 /brokers/topics 和 /config/topics 路径下，消息数据存储在 log.dir 或 log.dirs 配置的路径下，因此也可以手动删除主题：
```shell
rmr /brokers/topics/topic_delete
rmr /config/topics/topic_delete

rm -rf /<log.dir>/topic_delete
```
删除主题操作是不可逆的，一旦删除之后对应的消息数据也会全部删除且不可恢复。

### 主题参数

主题的参数基本上在 broker 上都有对应的配置，如果创建主题时没有指定参数则使用 broker 对应参数的默认值。

|topic 参数|broker 参数|默认值|含义|
|-|-|-|-|
|cleanup.policy|log.cleanup.policy|delete|日志压缩策略，可选 delete 和 compact|
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