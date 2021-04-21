## 分区管理

Kafka 集群不会自动迁移副本，当集群中的某个节点异常时，节点上的分区副本会失效，当增加节点时集群的负载不会均衡到新的节点。

Kafka 提供了脚本方式管理主题的分区，通过 `bin/kafka-reassign-partitions.sh` 工具可以在集群扩容或者节点异常时采用分区重新分配的方式迁移副本。

### 分区重分配

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

`kafka-reassign-partitions.sh` 脚本提供了选项 `--throttle` 和 `--replica-alter-log-dirs-throttle` 控制副本复制过程的流量。
```shll
# --throttle 指定跨 Broker 的分区迁移限流，单位 B/s，最少 1KB/s
# --replica-alter-log-dirs-throttle     指定同 Broker 不同目录的迁移限流，单位为 B/s，最少 1KB/s
bin/kafka-reassign-partitions.sh \
--execute \
--bootstrap-server localhost:9092 \
--reassignment-json-file throttle-reassgin.json \
--throttle 200
--replica-alter-log-dirs-throttle 1024
```

### 优先副本选举

Kafka 在重新选举副本的 Leader 之后由于 Leader 副本分布不均匀导致集群负载不均衡，Kafka 通过优先副本(Prefer Replica)选举的方式使得集群的 Leader 副本尽可能分布均衡，从而时集群保持均衡。

优先副本指的是 AR 集合中的第一个副本，通常为 Leader 副本，Kafka 保证优先副本在集群中是分布均衡的，因此通过将优先副本选举为 Leader 副本则能保证集群中分区的平衡，但是分区的平衡不代表集群负载的平衡，集群负载的平衡还受分区的负载等因素影响。

Kafka 默认开启了分区的自动均衡，通过设置参数 `auto.leader.rebalance.enable` 可以控制集群分区自动均衡的开启和关闭，分区自动平衡开启后，Kafka 的控制器会启动一个定时任务，这个定时任务会轮询所有的 broker 节点并计算每个 broker 节点的分区不平衡率(非优先副本 leader 个数/分区总数)是否超过 ```leader.imbalance.per.broker.percentage``` 参数配置的比值，默认是 10%，如果超过次比值则会自动执行优先副本的选举动作以求分区平衡，执行周期由 ```leader.imbalance.cheek.interval.seconds``` 控制，默认是 300s。

执行优先副本选举的过程会消耗集群的资源，影响线上业务，因此在生产环境中一般不会开启分区自动均衡，而是通过 Kafka 提供的脚本 `kafka-preferred-replica-election.sh` 来手动的执行优先副本的选举。

```shell
# prefer-replica-election.json  指定需要执行优先副本选举的主题分区
# {
#   "partitions":[
#     {"topic":"topic-prefer-election","partition":0},
#     {"topic":"topic-prefer-election2", "partition":1}
#   ]
# }

bin/kafka-preferred-replica-election.sh \
--bootstrap-server localhost:9092 \
--path-to-json-file prefer-replica-election.json
```
优先副本选举过程中的元数据会存入 ZooKeeper 的 `/admin/preferred_replica_election` 节点，如果节点中的数据过大则有可能会导致优先副本选举失败。

Kafka 提供了新的 Leader 选举脚本 `kafka-leader-election.sh` 可以用于优先副本选举以及 `Uncleaned` 副本的选举：
```shell
bin/kafka-leader-election.sh \
--bootstrap-server localhost:9092 \
--election-type preferred \
--topic topic-leader-election \
--partition 1 \
```