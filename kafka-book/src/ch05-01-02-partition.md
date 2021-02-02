# 分区管理

Kafka 集群不会自动迁移副本，当集群中的某个节点异常时，节点上的分区副本会失效，当增加节点时集群的负载不会均衡到新的节点。

Kafka 提供了脚本方式管理主题的分区，通过 `bin/kafka-reassign-partitions.sh` 工具可以在集群扩容或者节点异常时采用分区重新分配的方式迁移副本。

## 分区重分配

`kafka-reassign-partition.sh` 脚本提供了 `--generate` 选项用于生成分区分配方案，在生成分区分配方案时需要通过 `--topic-to-move-json-file` 指定需要重新分配分区的主题，并且需要通过 `--broker-list` 选项指定参与分区重分配的 Broker。

```shell
# reassign.json：
#   {"topics":[{"topic":"topic-reassign"}],"version":1}

# --generate 生成一个重分配的候选方案
# --topics-to-move-json-file 指定分区重分配对应的主题清单文件的路径
# --broker-list 指定用于分配的 broker 列表

bin/kafka-reassign-partitions.sh \
--generate \
--bootstrap-server localhost:9092 \
--topics-to-move-json-file reassign.json \
--broker-list 0,2
```
根据生成的分区重分配方案，使用 `kafka-reassign-partitions.sh` 脚本提供的 `--execute` 选项执行指定分区分配方案，分区分配方案通过选项 `--reassignment-json-file` 指定，文件中的内容就是生成的分区分配方案。
```shell
# project.json:
#   {"version":1,
#    "partitions":[
#       {"topic":"topic-reassignment","partition":0,"replicas":[2,1],"log_dirs":["any","any"]},
#       {"topic":"topic-reassignment","partition":1,"replicas":[1,2],"log_dirs":["any","any"]}
#    ]
#   }

# --execute 指定执行重分配的动作
# --reassignment-json-file 分区重分配方案的文件路径
bin/kafka-reassign-partitions.sh \
--execute \
--bootstrap-server localhost:9092 \
--reassignment-json-file project.json
```
`kafka-reassign-partitions.sh` 脚本还提供了 `--list` 选项查看进行中的分区重分配，`--cancel` 选项取消指定的分区重分配，`--verify` 验证分区重分配是否按照指定的方案完成。
```shell
bin/kafka-reassign-partitions.sh --list

bin/kafka-reassign-partitions.sh --cancel --reassignment-json-file reassignment-file.json

bin/kafka-reassign-partitions.sh --verify --reassignment-json-file reassignment-file.json
```

分区重分配的基本原理是先通过控制器为每个分区添加新的副本，然后副本从 leader 副本复制所有的数据，复制完成后控制器将旧的副本移除。分区重分配会占用集群大量的资源，在执行分区重分配时通常会限制副本间的复制流量从而减少对集群的影响。

`kafka-reassign-partitions.sh` 脚本提供了选项 `--throttle` 和 `--replica-alter-log-dir-throttle` 控制副本复制过程的流量。
```shll
bin/
```

## 复制限流




副本间的复制限流有两种实现方式：```kafka-configs.sh``` 脚本和 ```kafka-reassign-partitions.sh``` 脚本：

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


### 优先副本选举
当 leader 副本退出时 follower 副本被选择为新的 leader 从而导致集群的负载不均衡，为了治理负载失衡的情况，Kafka 引入了优先副本(preferred replica)的概念，优先副本指的是在 AR 集合列表中的第一个副本，理想情况下优先副本是分区的 leader 副本。Kafka 需要确保所有主题的优先副本在集群中均匀分布，这样也就保证了所有分区的 leader 副本均衡分布。

优先副本选举是指通过一定的方式促使优先副本选举为 leader 副本以此来促进集群的负载均衡，也称为 “分区平衡”。分区平衡并不意味着 Kafka 集群的负载均衡，还需要考虑分区的均衡和 leader 副本负载的均衡。

Kafka 提供分区自动平衡的功能，配置参数 ```auto.leader.rebalance.enable``` 为 true 就开启了分区自动平衡，默认是开启的。分区自动平衡开启后，Kafka 的控制器会启动一个定时任务，这个定时任务会轮询所有的 broker 节点并计算每个 broker 节点的分区不平衡率(非优先副本 leader 个数/分区总数)是否超过 ```leader.imbalance.per.broker.percentage``` 参数配置的比值，默认是 10%，如果超过次比值则会自动执行优先副本的选举动作以求分区平衡，执行周期由 ```leader.imbalance.cheek.interval.seconds``` 控制，默认是 300s。在生产环境中不建议开启，因为在执行优先副本选举的过程中会消耗集群的资源影响线上业务，更稳妥的方式是自己手动执行分区平衡。

Kafka 中的 ```kafka-preferred-replica-election.sh``` 脚本提供了对分区 leader 副本进行重新平衡的功能，优先副本的选举过程是一个安全的过程，Kafka 客户端可以自动感知分区 leader 副本的变更。
```shell
bin/kafka-preferred-replica-election.sh --zookeeper localhost:2181
```
Kafka 将会在集群上所有的分区都执行一遍优先副本的选举操作，如果分区比较多则有可能会失败；在优先副本选举的过程中元数据信息会存入 ZooKeeper 的 /admin/preferred_replica_election 节点，如果元数据过大则选举就会失败。```kafka-preferred-replica-election.sh``` 脚本提供了 path-to-json-file 参数来对指定分区执行优先副本的选举操作：
```
{
  "partitions":[
    {"partition":0,"topic":"topic-partitions"},
    {"partition":1,"topic":"topic-partitions"},
    {"partition":2,"topic":"topic-partitions"},
  ]
}

bin/kafka-preferred-replica-election.sh --zookeeper localhost:2181 --path-to-json-file election.json
```