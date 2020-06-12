## 分区

Kafka 以分区作为物理存储单位，每个主题 (Topic) 有一个或多个分区 (Partition)，Producer 发往 broker 的消息根据消息的 key 被分配到指定的分区之后被持久化到分区对应的节点上。

Kafka 使用多副本保证数据的可靠性，每个分区都有至少一个副本。其中 leader 副本负责对外提供读写服务，follower 副本负责同步 leader 副本上的数据，当 leader 副本不可用时需要根据选举策略从 follower 副本中选举出新的 leader 副本。

### 副本

每个分区有多个副本，Kafka 保证同一个分区的副本分布在不同的节点上。分区的所有副本集合为 AR(Assigned Replica)，和 leader 副本保持同步的 follower 副本集合为 ISR(In-sync Replica)，未能和 leader 副本保持同步的 follower 副本集合为 OSR(Outof-sync Rplica)，即 AR = ISR + OSR + leader。

副本的 LEO (LogEndOffset) 表示副本中最后一条消息的 offset + 1，ISR 中最小的 LEO 是整个分区的 HW，消费者只能拉取到 HW 之前的消息，因此消息只有在 ISR 中所有的副本同步之后才能被消费者拉取到。**ISR 发生变化或者 ISR 中任意一个副本的 LEO 发生变化都可能影响整个分区的 HW**

```
问题：如果在 ISR 同步数据完成前，leader 不可用，消息是否丢失？
```

#### ISR
Kafka 中 leader 是从 ISR 的副本中选举的，当副本不能与 leader 副本保持同步就需要将其移出 ISR 集合。Kafka 在启动 ```ReplicaMananger``` 时创建了 ```isr-expiration``` 线程监控 ISR 中副本的同步状态，该线程会以 ```replicaLagTimeMaxMs/2``` 的周期(```replica.lag.time.max.ms``` 设置，默认 10000ms)遍历 ISR 中的所有副本，当副本的 LEO 和 leader 副本的 LEO 不相等并且副本上次和 leader 副本保持一致的时间 (lastCaughtUpTimeMs) 与当前时间相差 ```replicaLagTimeMaxMs``` 则会被移出 ISR。即 **ISR 中的副本和 leader 副本不能保持同步的最长时间为 ```1.5 * replicaLagTimeMaxMs```**。
```java
private def isFollowerOutOfSync(replicaId: Int,
                                leaderEndOffset: Long,
                                currentTimeMs: Long,
                                maxLagMs: Long): Boolean = {
  val followerReplica = getReplicaOrException(replicaId)
  // 判断 follower 副本不在 ISR 集合
  followerReplica.logEndOffset != leaderEndOffset &&
    (currentTimeMs - followerReplica.lastCaughtUpTimeMs) > maxLagMs
}
```

副本被移出 ISR 一般有三种情况：
- follower 同步较慢，在一段时间内都无法追赶上 leader 副本。常见原因是 I/O 瓶颈导致 follower 追加复制消息速度比从 leader 拉取速度慢
- follower 进程卡住，在一段时间内根本没有向 leader 副本发起同步请求。一般是由于 follower 进程 GC 或者 follower 失效
- 新启动的 follower 副本，新增的 follower 副本在完全赶上 leader 副本之前不在 ISR 中

```isr-expiration``` 线程更新 ISR 后将新的 ISR 和 leader 数据记录到 ZK 上 ```/brokers/topics/<topic>/partitions/<paritition>/state``` 节点中：
```

```
记录到 ZK 上的数据包括：
- ```Leader``` 表示当前分区的 leader 副本所在的 broker 
- ```ISR``` 表示更新后的 ISR 集合
- ```LeaderEpoch``` 表示当前分区的 leader epoch
- ```ControllerEpoch``` 表示当前 Kafka 控制器的 epoch
- ```version``` 表示版本信息


ISR 集合发生变更时还会将变更的记录缓存到 ```isrChangeSet``` 中，ReplicaManager 启动时创建的线程 ```isr-chagne-propagation``` 会以 2500ms 的周期检查 ```isrChangeSet```，如果发现 ISR 集合上次变更到现在查过 5000ms 或者上次广播 ISR 集合变更到现在超过 60000ms 就需要将 ISR 集合变更广播：
```java
def maybePropagateIsrChanges(): Unit = {
  val now = System.currentTimeMillis()
  isrChangeSet synchronized {
    if (isrChangeSet.nonEmpty &&
    // 上次更新 isrChangeSet 到现在时间超过 5000ms
    (lastIsrChangeMs.get() + ReplicaManager.IsrChangePropagationBlackOut < now ||
        // 上次广播 ISR 变更时间到现在时间超过 60000ms
        lastIsrPropagationMs.get() + ReplicaManager.IsrChangePropagationInterval < now)) {
      zkClient.propagateIsrChanges(isrChangeSet)
      isrChangeSet.clear()
      lastIsrPropagationMs.set(now)
    }
  }
}
```
ISR 集合变更的广播是在 ZK 的 ```/isr_change_notification``` 下创建 ```isr_change_<seq>``` 的顺序持久节点，并将 ```isrChangeSet``` 中的数据保存到这个节点。
```
ls /isr_change_notification


```
Kafka 控制器为 ```/isr_change_notification``` 添加了一个 Watcher，当有子节点发生变化时会触发 Watcher 通知控制器更新相关元数据信息并向它管理的 broker 节点发送更新元数据的请求，最后删除 ```/isr_change_notification``` 节点下已经处理过的节点:
```java
```
检测到分区的 ISR 集合发生变化时，需要满足两个条件之一才能触发元数据的变更：
- 上一次 ISR 集合发生变化距离现在已经超过 5s
- 上一次写入 ZooKeeper 的时间距离现在已经超过 60s

当 follower 的 LEO 追赶上 leader 副本之后就可以进入 ISR 集合，追赶上的判定标准是此副本的 LEO 不小于 leader 副本的 HW，ISR 扩充之后同样会更新 ZooKeeper 中的 /brokers/topics/<topic>/paritition/<partition>/state 节点和 isrChangeSet


#### 恢复点
Kafka 中分区的信息被副本所在的 broker 节点上的 ```ReplicaMananger``` 管理。leader 副本记录了所有副本的 LEO，而其他 follower 副本只记录了自己的 LEO，leader 副本在收到 follower 副本的 FetchRequest 请求之后在将数据返回给 follower 副本之前会先更新对应的 LEO。

Kafka 使用 ```recovery-point-offset-checkpoint``` 和 ```replication-offset-checkpoint``` 两个文件分别记录分区的 LEO 和 HW。LogManager 在启动时创建线程用于周期性的刷写数据到这两个文件，其中 ```kafka-recovery-point-checkpoint``` 线程定期将分区的 LEO 刷写到 ```recovery-point-offset-checkpoint``` 文件中，周期为参数 ```log.flush.offset.checkpoint.interval.ms``` 设置，默认为 60000ms；``` ...``` 线程定期将所有分区的 HW 刷写到 ```replication-offset-checkpoint``` 文件中，周期由参数 ```replica.high.watermark.checkpoint.interval.ms``` 设置，默认为 5000ms。

分区日志的删除或者手动删除消息会导致日志的 ```LogStartOffset``` 增长，Kafka 将 ```LogStartOffset``` 持久化在 ```log-start-offset-checkpoint``` 文件中，ReplicaManager 在启动时创建 ```kafka-log-start-offset-checkpoint``` 线程将所有分区的 ```LogStartOffset``` 刷写到文件中，周期由参数 ```log.flush.start.offset.checkpoint.interval.ms``` 设置，默认为 60000ms。

### 副本分配

Kafka 保证同一分区的不同副本分配在不同的节点上，不同分区的 leader 副本尽可能的均匀分布在集群的节点中以保证整个集群的负载均衡。

在创建主题的时候，如果通过 ```replica-assignment``` 指定了副本分配方案则按照指定的方案分配，否则使用默认的副本分配方案。使用 ```kafka-topics.sh``` 脚本创建主题时 ```AdminUtils``` 根据是否指定了机架信息(```broker.rack``` 参数，所有主题都需要指定)分为两种分配策略：
- ```assignReplicasToBrokersRackUnaware```：无机架分配方案
- ```assignReplicasToBrokersRackAware```：有机架分配方案

无机架分配方案直接遍历分区分配分区的每个副本，从一个指定的 broker 开始，副本在第一个副本指定间隔之后以轮询的方式分配在 broker 上。
```java
private def assignReplicasToBrokersRackUnaware(nPartitions: Int,
                                               replicationFactor: Int,
                                               brokerList: Seq[Int],
                                               fixedStartIndex: Int,
                                               startPartitionId: Int): Map[Int, Seq[Int]] = {
  // 存储分配方案
  val ret = mutable.Map[Int, Seq[Int]]()
  val brokerArray = brokerList.toArray
  // 分配的 broker 起始 Id
  val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
  // 分配的 partition 起始 Id
  var currentPartitionId = math.max(0, startPartitionId)
  // partition 中与第一个副本的间隔
  var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
  // 遍历分配分区
  for (_ <- 0 until nPartitions) {
    // 分区数大于 broker 数量，则每分配一轮就将间隔 +1
    if (currentPartitionId > 0 && (currentPartitionId % brokerArray.length == 0))
        nextReplicaShift += 1
    // 计算第一个副本分配的 brokerId
    val firstReplicaIndex = (currentPartitionId + startIndex) % brokerArray.length
    // 存储分区的分配方案
    val replicaBuffer = mutable.ArrayBuffer(brokerArray(firstReplicaIndex))
    // 遍历分区副本分配每个副本
    for (j <- 0 until replicationFactor - 1)
      // 计算副本分配的 brokerId
      replicaBuffer += brokerArray(replicaIndex(firstReplicaIndex, nextReplicaShift, j, brokerArray.length))
    ret.put(currentPartitionId, replicaBuffer)
    currentPartitionId += 1
  }
  ret
}

private def replicaIndex(firstReplicaIndex: Int, secondReplicaShift: Int, replicaIndex: Int, nBrokers: Int): Int = {
  // 计算与第一个副本的间隔
  val shift = 1 + (secondReplicaShift + replicaIndex) % (nBrokers - 1)
  (firstReplicaIndex + shift) % nBrokers
}
```
有机架的分配策略在分配的过程中加入了机架的影响，对同一个分区来说，当出现两种情况时当前 broker 上不分配副本：
- 当前 broker 所在的机架上已经存在分配了副本的 broker 并且存在还没有分配副本的机架
- 当前 broker 已经分配了副本并且存在还没有分配副本的 broker
```java
private def assignReplicasToBrokersRackAware(nPartitions: Int,
                                             replicationFactor: Int,
                                             brokerMetadatas: Seq[BrokerMetadata],
                                             fixedStartIndex: Int,
                                             startPartitionId: Int): Map[Int, Seq[Int]] = {
  // 获取 broker 和 机架的映射
  val brokerRackMap = brokerMetadatas.collect { case BrokerMetadata(id, Some(rack)) =>
    id -> rack
  }.toMap
  val numRacks = brokerRackMap.values.toSet.size
  // 按机架排序的 broker 列表
  val arrangedBrokerList = getRackAlternatedBrokerList(brokerRackMap)
  val numBrokers = arrangedBrokerList.size
  // 存储副本分配方案
  val ret = mutable.Map[Int, Seq[Int]]()
  val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
  var currentPartitionId = math.max(0, startPartitionId)
  var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
  // 遍历分区
  for (_ <- 0 until nPartitions) {
    if (currentPartitionId > 0 && (currentPartitionId % arrangedBrokerList.size == 0))
      nextReplicaShift += 1
    val firstReplicaIndex = (currentPartitionId + startIndex) % arrangedBrokerList.size
    val leader = arrangedBrokerList(firstReplicaIndex)
    val replicaBuffer = mutable.ArrayBuffer(leader)
    // 存储已经分配过副本的机架
    val racksWithReplicas = mutable.Set(brokerRackMap(leader))
    // 存储已经分配过副本的 broker
    val brokersWithReplicas = mutable.Set(leader)
    var k = 0
    // 遍历分配副本
    for (_ <- 0 until replicationFactor - 1) {
      var done = false
      while (!done) {
        // 副本分配的 broker
        val broker = arrangedBrokerList(replicaIndex(firstReplicaIndex, nextReplicaShift * numRacks, k, arrangedBrokerList.size))
        // broker 对应的机架
        val rack = brokerRackMap(broker)
          // Skip this broker if
          // 1. there is already a broker in the same rack that has assigned a replica AND there is one or more racks
          //    that do not have any replica, or
          // 2. the broker has already assigned a replica AND there is one or more brokers that do not have replica assigned
        if ((!racksWithReplicas.contains(rack) || racksWithReplicas.size == numRacks)
            && (!brokersWithReplicas.contains(broker) || brokersWithReplicas.size == numBrokers)) {
          replicaBuffer += broker
          racksWithReplicas += rack
          brokersWithReplicas += broker
          done = true
        }
        k += 1
      }
    }
    ret.put(currentPartitionId, replicaBuffer)
    currentPartitionId += 1
  }
  ret
}
```

#### 重分配



### 副本同步

follower 副本向 leader 副本拉取数据的细节在 ```ReplicaManager#makeFollower``` 中。

https://www.jianshu.com/p/f9a825c0087a

### Leader 选举

#### Leader Epoch


#### 优先副本的选举
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

#### 分区重分配
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
#### 复制限流
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

#### 带限流的分区重新分配
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




