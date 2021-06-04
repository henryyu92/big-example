## ReplicaManager

`ReplicaManager` 负责和 `KafkaController` 通信从而管理当前 broker 的所有分区和副本信息。

Kafka 使用多副本保证数据的可靠性，每个分区都有至少一个副本，其中 leader 副本负责对外提供读写服务，follower 副本负责同步 leader 副本上的数据，当 leader 副本不可用时需要根据选举策略从 follower 副本中选举出新的 leader 副本。

为了平衡数据写入的效率和数据的可靠性，Kafka 引入副本集合的概念，每个分区的副本都会划分到三个集合中：

- **AR(Assigned Replica)**： 分区中所有副本的集合
- **ISR(In-Sync Replica)**： 和 Leader 副本保持同步的副本集合，包括 Leader 副本
- **OSR(Out-of-Sync Replica)**： 没有和 Leader 副本保持同步的副本集合

数据只有写入 ISR 集合中的所有副本后才能被读取，当 Leader 副本异常时只有 ISR 集合中的副本才能参与 Leader 选举。

Kafka 定义了 `LEO(LogEndOffset)` 表示副本中最后的 offset，ISR 中最小的 LEO 则表示整个分区的 `HW(High Watermark)`，Kafka 定义只有 HW 之前的数据可以被读取，因此 Kafka 需要保证 ISR 集合中的副本尽量和 Leader 副本保持一致从而保证集群的吞吐量。

### ISR

Kafka 在启动 `ReplicaManager` 时创建了 `isr-expiration` 线程监控分区 IRS 集合的变更。
```scala
def startup(): Unit = {
  // 周期性的检查所有分区的 ISR 是否需要收缩
  scheduler.schedule("isr-expiration", maybeShrinkIsr _, period = config.replicaLagTimeMaxMs / 2, unit = TimeUnit.MILLISECONDS)
}
```
`isr-expiration` 线程以 `replicaLagTimeMaxMs/2` 的周期(```replica.lag.time.max.ms``` 设置，默认 10000ms)遍历所有分区并将不能和 leader 副本保持同步 (`LEO` 相同) 的副本从 isr 集合中删除。

副本不能和 `leader` 副本保持同步是通过计算 `follower` 副本上次和 `leader` 保持同步的时间戳 (`lastCaughtUpTimeMs`) 和当前时间的时差大于 ``replicaLagTimeMaxMs`，也就是说 isr 集合中的副本和 `leader` 副本不能保持同步的最长时间为 `1.5*replicaLagTimeMaxMs`。

```scala
val laggingReplicas = candidateReplicas.filter(r => (time.milliseconds - r.lastCaughtUpTimeMs) > maxLagMs)
```

副本不能和 `leader` 副本保持同步会在两种情况下发生：

- `follower` 同步较慢，在 `replicaLagTimeMaxMs` 时间内都不能和 `leader` 副本保持一致
- `follower` 由于某些原因在 `replicaLagTimeMaxMs` 时间内没有从 `leader` 同步数据

`ISR` 集合更新后，```isr-expiration``` 线程会将新的 `ISR` 集合更新到 ZooKeeper 上。数据记录在 ```/brokers/topics/<topic>/partitions/<paritition>/state``` 节点中，记录的数据包括：

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



### LEO 和 HW

`LEO(LogEndOffset)` 表示副本下一个写入消息的 `offset`，`HW(High Watermark)` 表示副本第一个不可读取的消息的 `offset`。**ISR 集合中最小的 `LEO` 则表示整个分区的 `HW`**。

Kafka 的所有副本都有 `LEO`，`leader` 副本在消息写入日志后会立即更新，而 `follower` 副本需要通过发送 `FetchRequest` 请求拉取消息之后才会更新。

Kakfa 的所有副本也都有 `HW`，`leader` 副本会更新为 ISR 集合中副本的 `FetchRequest` 请求携带的 `LEO` 的最小值，而 `follower` 副本则更新为响应中携带的 `HW` 和自身的 `LEO` 的最小值。



### CheckPoint

Kafka 中分区的信息被副本所在的 broker 节点上的 ```ReplicaMananger``` 管理。leader 副本记录了所有副本的 LEO，而其他 follower 副本只记录了自己的 LEO，leader 副本在收到 follower 副本的 FetchRequest 请求之后在将数据返回给 follower 副本之前会先更新对应的 LEO。

Kafka 使用 ```recovery-point-offset-checkpoint``` 和 ```replication-offset-checkpoint``` 两个文件分别记录分区的 LEO 和 HW。LogManager 在启动时创建线程用于周期性的刷写数据到这两个文件，其中 ```kafka-recovery-point-checkpoint``` 线程定期将分区的 LEO 刷写到 ```recovery-point-offset-checkpoint``` 文件中，周期为参数 ```log.flush.offset.checkpoint.interval.ms``` 设置，默认为 60000ms；``` ...``` 线程定期将所有分区的 HW 刷写到 ```replication-offset-checkpoint``` 文件中，周期由参数 ```replica.high.watermark.checkpoint.interval.ms``` 设置，默认为 5000ms。

分区日志的删除或者手动删除消息会导致日志的 ```LogStartOffset``` 增长，Kafka 将 ```LogStartOffset``` 持久化在 ```log-start-offset-checkpoint``` 文件中，ReplicaManager 在启动时创建 ```kafka-log-start-offset-checkpoint``` 线程将所有分区的 ```LogStartOffset``` 刷写到文件中，周期由参数 ```log.flush.start.offset.checkpoint.interval.ms``` 设置，默认为 60000ms。



https://www.jianshu.com/p/f9a825c0087a



https://blog.csdn.net/wl044090432/article/details/51035614