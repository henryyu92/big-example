# 分区管理

## 修改副本因子
修改副本因子的功能是通过重分配所使用的 ```kafka-reassign-partitions.sh``` 来实现的，只需要在 JSON 文件中增加或减少 replicas 的参数即可：
```shell
{
  "topic":"topic-throttle",
  "pritition":1,
  "replicas":[0,1,2],
  "log_dirs":["any","any","any"]
}

bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 \
--execute --reassignment-json-file add.json
```

## 分区重分配
当集群中的一个节点宕机时，该节点上的分区副本都会失效，Kafka 不会将这些失效的分区副本自动的转移到集群中的其他节点；当新增节点时，只有新创建的主题才能分配到该节点而之前的主题不会自动转移到该节点。

为了保证分区及副本再次进行合理的分配，Kafka 提供了 ```kafka-reassign-partitions.sh``` 脚本来执行分区重新分配的工作，它可以在集群扩容、broker 节点失效的场景下对分区进行迁移。

```kafka-reassign-partitions.sh``` 脚本使用时需要先创建包含主题清单的 JSON 文件，然后根据主题清单和 broker 节点清单生成一份重分配方案，最后根据分配方案执行具体的重分配动作。
```shell
reassign.json：
{"topics":[{"topic":"topic-reassign"}],"version":1}

# --generate 用于生成一个重分配的候选方案
# --topics-to-move-json-file 用来指定分区重分配对应的主题清单文件的路径
# --broker-list 用来指定所要分配的 broker 节点列表
bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 \
--generate --topics-to-move-json-file reassign.json \
--broker-list 0,2

# --execute 用于指定执行重分配的动作
# --reassignment-json-file 指定分区重分配方案的文件路径(重分配方案即上个脚本的输出)
bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 --execute --reassignment-json-file project.json
```
除了脚本自动生成分区重分配候选方案，也可以自定义分区分配方案，只需要自定义分区分配的 json 文件即可。

分区重分配的基本原理是先通过控制器为每个分区添加新的副本，新的副本将从分区的 leader 副本复制所有的数据，在复制完成之后控制器将旧的副本从副本清单里移除。使用 verify 指定可以查看分区重分配的进度：
```shell
bin/kafka-reassign-partitions.sh --zookeeper localhost:2181 \
--verify --reassignment-json-file project.json
```
分区重分配对集群的性能有很大影响，在实际操作中可以降低重分配的粒度，分批次来执行重分配以减少带来的影响。如果要将某个 broker 下线那么在执行分区重分配操作之前最好关闭或重启 broker，这样这个 broker 就不包含 leader 副本可以提升重分配的性能，减少对集群的影响。

## 复制限流
分区重分配的本质在于数据复制，当重分配的量比较大则会影响集群的性能因此需要对副本间的复制流量进行限制来保证重分配期间整个服务不会受太大影响。

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