# 配置管理

Kafka 提供了 `kafka-configs.sh` 脚本用于在集群运行状态下修改集群的配置参数从而达到动态变更配置的目的。

`kafka-confgis.sh` 脚本支持主题、broker、客户端、用户的参数动态配置，并提供了 `--alter` 指令修改配置参数以及 `--describe` 指令查看配置参数信息。

使用 `kafka-configs.sh` 脚本管理配置时需要通过 `--entity-type` 和 `entity-name` 参数指定需要操作的类型



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

### 限流
```kafka-configs.sh``` 脚本主要以动态配置的方式来达到限流的目的。在 broker 级别通过 ```follower.replication.throttled.rate``` 和 ```leader.replication.throttled.rate``` 参数分别设置 follower 副本复制的速度和 leader 副本传输的速度，都是 B/s：
```shell
bin/kafka-configs.sh --zookeeper localhost:2181 \
--entity-type brokers --entity-name 1 \
--alter \
--add-config follower.replication.throttled.rate=1024,leader.replication.throttled.rate=1024

# 删除配置
bin/kafka-configs.sh --zookeeper localhost:2181 \
--entity-type brokers --entity-name 1 --alter \
--delete-config follower.replication-throttled.rate,leader.replication.throttled.rate
```
在主题级别通过参数```leader.replication.throttled.replicas``` 和 ```follower.replication.throttled.replicas``` 分别用来配置被限制速度的主题所对应的 leader 副本列表和 follower 副本列表：
```shell
bin/kafka-configs.sh --zookeeper localhost:2181 \
--entity-type topics --entity-name topic-throttle \
--alter --add-config leader.replication.throttled.replicas=[0:0,1:1,2:2], \
follower.replication.throttled.replicas=[0:1,1:2,2:0]
```
leader.replication.throttled.replicas 配置了分区与 leader 副本所在的 broker 的映射；follower.replication.throttled.replicas 配置了分区与 follower 副本所在 broker 的映射；映射的格式为 parititonId:brokerId

```kafka-reassign-partitions.sh``` 脚本指定 throttle 参数也能提供限流功能，其实现原理也是设置与限流相关的 4 个参数：
```shell
bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 \
--execute --reassignment-json-file project.json \
--throttle 10
```
### 带限流的分区重新分配
在分区重分配的时候，可以设置限流以避免数据复制导致负载太大。在执行分区重分配前还是需要生成重分配的候选策略：
```shell
# 创建包含可行性方案的 project.json
{
  "version":1,
  "partitions":[
    {"topic":"topic-throttle","partition":1,"replicas":[2,0],"log_dirs":["any","any"]},
    {"topic":"topic-throttle","partition":0,"replicas":[0,2],"log_dirs":["any","any"]},
    {"topic":"topic-throttle","partition":2,"replicas":[0,2],"log_dirs":["any","any"]}
  ]
}
```
根据分区策略可以设置限流方案：
- 与 leader 有关的限制会应用于重分配前的所有副本，因为任何一个副本都可能是 leader
- 与 follower 有关的限制会应用于所有移动的目的地
```
bin/kafka-configs.sh --zookeeper localhost:2181 \
--entity-type topics --entity-name topic-throttle \
--alter --add-config leader.replication.throttled.replicas=[1:1,1:2,0:0,0:1], \
follower.replication.throttled.replicas=[1:0,0:2]
```
设置 broker 的复制速度
```
bin/kafka-configs.sh --zookeeper localhost:2181 \
--entity-type brokers --entity-name 2 --alter \
--add-config follower.replication.throttled.rate=10, \
leader.replication.throttled.rate=10
```
执行分区重分配
```
bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 \
--execute --reassignment-json-file project.json
```
查看分区重分配进度，使用 verify 指令查看分区重分配时，如果重分配已经完成则会清除之前的限流设置：
```
bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 \
--verify --reassignment-json-file project.json
```
