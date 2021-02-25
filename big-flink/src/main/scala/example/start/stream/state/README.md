## 状态管理

有状态计算是指在程序计算过程中，在 Flink 程序内部存储计算产生的中间结果，并提供给后续 Function 或者算子使用，状态数据存储在本地堆内存或者堆外内存，或者第三方存储介质。

无状态计算不会存储计算过程中产生的结果，也不会将结果用于下一步计算过程中，程序只会在当前的计算流程中执行计算，计算完成后输出结果。

Flink 中根据数据集是否根据 Key 进行分区，将状态分为 KeyedState 和 OperatorState 两中类型。KeyedState 是和 key 相关的一种 State，只能用于 KeyedStream 数据集对应的 Functions 和 Operators 之上，KeyedState 通过 keyGroups 进行管理，主要用于当算子并行度发生变化时自动重新分布 KeyedState 数据。

OperatorState 只和并行的算子实例绑定，和数据元素的 key 无关，每个算子实例中持有所有数据元素中的一部分状态数据，OperatorState 支持当算子实例并行度发生变化时自动重新分配状态数据。

Flink 中的两种状态都具有 ManagedStat(托管状态) 和 RawState(原生状态) 两种形式，托管状态由 Flink Runtime 中控制和管理状态数据，并将状态数据转换成内存 HashTable 或者 RocksDB 的对象存储，然后将这些状态通过内部的接口持久化到 Checkpoints 中，任务异常时可以通过这些状态恢复任务。

原生状态形式由算子自己管理数据结构，当触发 Checkpoint 过程时，Flink 并不知道状态数据内部的数据结构，只是将数据转换成 byte 数据存储在 Checkpoint 中，当从 Checkpoints 恢复任务时，算子自己再反序列化出状态的数据结构。

Flink 推荐使用 ManagedState 管理状态数据，以便能更加友好地支持状态数据地重平衡以及更加完善地内存管理。

### ManagedKeyedState

Flink 定义了多个 ManagedKeydState 类型，每种状态都有相应地使用场景
- ValueState[T]：与 key 对应单个值的状态，ValueSate 对应的更新方法为 update，取值方法为 value
- ListState[T]：与 key 对用元素列表的状态，状态中存放元素的 List 列表，ListState 中添加元素使用 add 或者 addAll 方法，获取原始使用 get 方法，更新元素使用 update 方法
- ReducingState[T]：定义与 key 相关的数据元素单个聚合值的状态，用于存储经过指定 ReduceFunction 计算之后的指标，ReducingState 需要指定 ReduceFunction 完成状态数据的聚合
- AggregateState[IN, OUT]：定义与 key 对应的数据元素单个聚合值的状态，用于维护数据元素经过指定 AggregateFunction 计算之后的指标
- MapState[UK, UV]：定于与 key 对应键值对的状态，用于维护具有 key-value 结构类型的状态数据

Flink 中需要通过创建 StateDescriptor 来获取相应的 State 的操作类，StateDescriptor 主要定义了状态的名称、状态中数据的类型参数信息以及状态自定义函数，每种 ManagedKeyedState 由相应的 StateDescriptor。

```scala
val env = StreamExecutionEnvironment.getExecutionEnvironment
val input = env.fromElements((2, 21L), (4, 1L), (5, 4L))
input.keyBy(_._1).flatMap{
  // 定义 RichFlatMapFunction
  new RichFlatMapFunction[(Int, Long), (Int, Long, Long)] {
    private var leastValueState: ValueState[Long] = _
    override def open(parameters: Configuration): Unit = {
      val leastValueStateDescriptor = new ValueStateDescriptor[Long]("leastValue", classOf[Long])
      // 获取 ValueState
      leastValueState = getRuntimeContext.getState(leastValueStateDescriptor)
    } 
    override def flatMap(t: (Int, Long), collector: Collector[(Int, Long, Long)]):Unit = {
      // 取出状态值
      val leastValue = leastValueState.value()
      if (t._2 > leastValue){
        collector.collect((t._1, t._2, leastValue))  
      }else{
        // 更新状态
        leastValueState.update(t._2)
        collector.collect((t._1, t._2, t._2))
      } 
    }
  }
}
```
RichFunction 提供的 RuntimeContext 可以获取 ManagedKeyedState，通过创建相应的 StateDescriptor 并调用 RuntimeContext 的对应方法即可获取状态数据。

任何类型的 KeyedState 都可以设定状态的生命周期(TTL) 以确保能够再规定时间内及时地清理状态数据。状态生命周期功能可以通过 StateTtlConfig 配置然后传入 StateDescriptor 中地 enableTimeToLive 方法中即可。
```scala
val stateTtlConfig = StateTtlConfig.newBuilder(Time.seconds(10))
    // 设置 TTL 刷新机制
    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
    // 设置过期数据地可见性
    .setStateVisibility(StateTtlConfig.StateVisibility.NewverReturnExpired)
    .build
val valueStateDescriptor = new ValueStateDescriptor[String]("valueState", classOf[Long])
valueStateDescriptor.enableTimeToLive(stateTtlConfig)
```

除了通过 RichFunction 操作状态外，Flink 还提供了快捷地 API 来创建和查询状态数据。KeyedStream 中提供了 filterWithState, mapWithState, flatMapWithState 三种方法定义和操作状态数据。
```scala
val inputStream: DataStream[(Int, Long)] = env.fromElements((2, 2L), (4, 1L), (5, 4L))
val counts: DataStream[(Int, Int)] = inputStream.keyBy(_._1).mapWithState((in: (Int, Long), count:Option[Int]) => {
  count match {
    case Some(c) => ((in._1, c), Some(c + in._2))
    case None => ((in._1, 0), Some(in._2))
  }
})
```

### ManagedOperatorState
 OperatorState 与并行的操作算子实例相关联，在 Flink 中可以实现 CheckpointFunction 或者 ListCheckpoint 两个接口来定义操作 ManagedOperatorState 的函数。
 ```java
public interface CheckpointedFunction{

    // 每当 checkoutpoint 触发时调用
	void snapshotState(FunctionSnapshotContext context) throws Exception;
	// 自定义函数初始化时调用
	void initializeState(FunctionInitializationContext context) throws Exception;
} 
```
ManagedOperatorState 是以 List 形式存储，算子和算子之间的状态数据相互独立，Flink 目前支持对 ManagedOperatorState 两种重分布策略，分别是 EventSplitRedistribution 和 UnionRedistribution。
- EvenSplitRedistribution：每个算子实例中含有部分状态数据的 List 列表，整个状态数据是所有 List 列表的集合，当触发 restore/redistribution 动作时通过将状态数据平均分配成与算子并行度相同数量的 List 列表，每个 Task 实例中有一个 List
- UnionRedistribution：每个算子实例中含有所有状态元素的 List 列表，当触发 restore/redistribution 动作时，每个算子都能够获取到完整的状态元素列表

对于状态数据重分布策略的使用可以在创建 OperatorState 的过程中通过相应的方法指定：如果使用 EvenSplitRedistribution 策略则通过 context.getListState 获取 OperatorState，如果使用 UnionRedistribution 策略则通过 context.getUnionListState 来获取。

ListCheckoutpointed 接口和 CheckpointedFunction 接口相比灵活性上相对弱一些，只能支持 List 类型的状态，并且在数据恢复的时候仅支持 evenRedistribution 策略
```java
public ListCheckpointed {
    // 定义数据元素 List 存储到 checkpoints 的逻辑
	List<T> snapshotState(long checkpointId, long timestamp) throws Exception;
    // 定义从 checkpoints 中恢复状态的逻辑
	void restoreState(List<T> state) throws Exception;
}
```

## Checkpoint 和 Savepoint

有状态应用的 Checkpoint 就是所欲任务的状态，在某个时间点的快照，这个时间点应该是所有任务都恰好处理完一个相同的输入数据的时候


Flink 基于异步轻量级的分布式快照技术提供了 Checkpoints 容错机制，分布式快照可以将同一时间点 Task/Operator 的状态数据全局同一快照处理。

Flink 会在输入的数据集上间隔性的生成 checkpoint barrier，通过 barrier 将将间隔时间段内的数据划分到相应的 checkpoint 中，当出现异常时 Operator 就能够从上一次快照中恢复所有算子之前的状态，从而保持数据的一致性。

对于状态占用空间比较小的应用，快照产生过程非常轻量，高频率创建对 Flink 任务性能影响相对较小。checkpoint 过程中状态数据一般被保存在一个可配置的环境中，通常是在 JobManager 节点或 HDFS 上。

默认情况下 Flink 不开启检查点，需要在程序中通过调用 enableCheckpointing 方法配置和开启检查点，Flink 还提供了检查点的多个配置参数，通过获取的 CheckpointConfig 配置：
- interval：checkpoint barrier 的生成时间间隔
- checkpointMode：设置 chekpoint 的模式，可以是 exactly-once 或者 at-least-once 两种
- timeout：设置 checkpoint 执行过程中的超时时间，超时则会中断 checkpoint 过程并按照超时处理，默认 10m
- minPauseBetweenCheckpoints：设定两个 Checkpoint 之间的最小间隔，防止出现状态数据过大而导致 Checkpoint 执行时间过长从而导致 Checkpoint 积压过多从而使得密集的触发 Checkpoint 操作占用大量计算资源影响整个应用
- maxConcurrentCheckpoints：设置最大能够同时执行 Checkpoint 的数量，默认为 1
- enableExternalizedCheckpoints：设置周期性的外部检查点，然后将状态数据持久化到外部系统中，使用这种方式不会在任务正常的过程中清理掉检查点数据
- failOnCheckpointingErrors：设置当 Checkpoint 执行过程中如果出现失败或者错误时，任务是否同时被关闭，默认值为 true

```scala
val checkpointConfig = env.getCheckoutpointConfig()
checkpointConfig.setCheckpointingPointMode(CheckpointingMode.EXECTLY_ONCE)
```

Checkpoint 基于 Chandy-Lamport 算法实现分布式快照，将检查点的保存和数据处理分开，不暂停整个应用。

- JobManager 会向每个 source 任务发送一条带有新检查点 id 的消息从而启动检查点
- Source Operator 将自己的状态写入检查点，并发出一个检查点 barrier
- StateBackend 将状态存入检查点之后，会返回通知给 source 任务，source 任务就会向 JobManager 确认检查点完成
- barrier 向下游传递，下游算子必须等到所有分区的 barrier 到达之后才会将状态写入检查点，在处理状态之前到达的数据会被缓存
- 当收到上游算子所有分区的 barrier 时，任务就将其状态保存到 StateBackend 的检查点中，然后将 barrier 继续向下游转发
- 向下游算子转发 barrier 后，任务继续正常处理数据
- Sink 任务向 JobManager 确认状态保存到 checkpoint 完毕
- 当所有任务都确认已成功将状态保存到 checkpoint 时，检查点真正完成

### Savepoints

Savepoints 是检查点的一种特殊实现，底层其实也是使用 Checkpoints 的机制，Savepoints 是用户以手工命令的方式触发 Checkpoint 并将结果持久到指定的存储路径中，其目的是帮助用户在升级和维护集群过程中保存系统中的状态数据，避免因为停机运维或者升级应用等正常终止应用的操作而导致系统无法恢复到原有的计算状态的情况，从而无法实现端到端的 Excatly-Once 语义保证。

当使用 Savepoint 对整个集群进行升级或运维操作的时候，需要停止整个 Flink 应用程序，此时应用程序的代码逻辑会进行修改，即使 Flink 能够通过 Savepoint 将应用中的状态数据同步到磁盘然后再恢复任务，但是由于代码逻辑发生了变化，再升级过程中有可能导致算子的状态无法通过 Savepoint 中的数据恢复的情况，这种情况就需要通过唯一的 ID 标记算子。

Flink 默认支持自动生成 Operator ID，可以通过手动的方式对算子进行唯一 ID 标记，ID 的应用范围再每个算子内部，通过 uid 方法可以指定算子唯一 ID
```scala
env.addSource(new StatefulSource()).uid("source-id")
  .shuffle().map(new StatefulMapper()).uid("mapper-id")
  .print    // 自动生成 ID
```
Savepoint 操作可以通过命令行的方式进行触发，命令行提供了取消任务、从 Savepoint 中恢复任务、撤销 Savepoint 等操作。

触发 Savepoint 操作需要使用 Flink 命令提供的 savepoint 子命令
```shell script
# jobId 是需要触发 Savepoint 操作的 JobId
# targetDirectory 指定 Savepoint 数据存储路径
bin/flink savepoint :jobId [:targetDiretory]

# Yarn 提交的应用需要指定 YarnAppId
bin/flink savepoint :jobId [:targetDirectory] -yid: yarnAppId
```
取消 Flink 任务的同时将自动触发 Savepoint 操作，并把中间状态数据写入磁盘，用以后续的任务恢复
```shell script
bin/flink cancel -s [:targetDirectory] :jobId
```
使用 run 命令可以将任务从保存的 Savepoint 中恢复，其中 -s 参数指定了 Savepoint 数据存储路径。在由于应用中的算子和 Savepoint 中算子不一致是可以通过 --allowNonRestoredState 参数来设置忽略状态无法匹配的问题
```shell script
bin/flink run -s :savepointPath [:runArgs]
```
通过 --dispose 命令清除存储在指定路径中的 Savepoint 数据
```shell script
bin/flink savepoint -d :savepointPath
```
除了在执行 savepoint 命令时指定 Savepoint 数据的存储路径外，还可以使用配置文件中配置的路劲。flink-conf.yaml 配置文件中配置 state.savepoint.dir 参数可以配置 Savepoint 数据默认存储位置，路径需要是 TaskManager 和 JobManager 都能够反问道的路径。
```
state.savepoints.dir: hdfs:///flink/savepoints
```

### 状态管理器

每传入一条数据，有状态的算子任务都会读取和更新状态，由于有效的状态访问对于处理数据的低延迟至关重要，因此每个并行任务都会在本地维护其状态，确保快速的状态访问

状态的存储、访问以及维护由可插入的组件 StateBackend 决定，这个组件负责本地的状态管理以及检查点状态写入存储



Flink 提供了 StateBackend 来存储和管理 Checkpoint 过程中的状态数据。Flink 实现了三种类型的状态管理器，包括基于内存的 MemoryStateBackend，基于文件系统的 FsStateBackend 和基于 RocksDB 作为存储介质的 RocksDBStateBackend。默认情况下 Flink 使用的是内存作为状态管理器。

#### MemoryStateBackend

KeyedState 作为内存中的对象进行管理，将它们存储在 TaskManager 的 JVM 堆上，而将 checkpoint 存储在 JobManager 的内存中。

基于内存的状态管理器将状态数据全部存储在 JVM 堆内存中，包括用户在使用 DataStream API 中创建的 Key/Value State，窗口中缓存的状态数据，以及触发器等数据。

基于内存的状态管理具有非常快速和高效的特定，但是会有容量的限制，同时如果机器异常会导致整个状态数据丢失而无法恢复。Flink 默认使用 MemoryStateBackend 作为状态管理器，在初始化 MemoryStateBackend 时可以指定每个状态值的内存大小、
```scala
new MemoryStateBackend(MAX_MEM_STATE_SIZE, false)
```
Flink 中使用 MemoryStateBackend 具有如下特点，使用时需要注意：
- 聚合算子的状态会存储在 JobManager 内存中，因此对于聚合类算子比较多的应用会对 JobManager 的内存有一定的压力，进而对整个集群造成较大负担
- 状态数据传输的大小也受限于 akka.framesize 大小限制
- JVM 内存容量受限于主机内存大小，对于非常大的状态数据不适合使用 MemoryStateBackend 存储

#### FsStateBackend

将 checkpoint 存储到远程的持久化文件系统上，对于本地状态则会存储在 TaskManager 的 JVM 堆上

FaStateBackend 是基于文件系统的一种状态管理器，文件系统可以是本地文件系统，也可以是 HDFS 分布式文件系统。FsStateBackend 默认采用异步的方式将状态数据同步到文件系统中，异步方式能够尽可能避免在 Checkpoint 的过程中影响流式计算任务。
```java
new FsStateBackend(path, false)
```

#### RocksDBStateBackend

RocksDBStateBackend 是 Flink 中内置的第三方状态管理器，需要单独引入相关的依赖包到工程中。RocksDBStateBackend 采用异步的方式进行状态数据的 Snapshot，任务中的状态数据首先被写入 RocksDB 中，然后再异步的将状态数据写入文件系统中。

RocksDB 通过 jni 的方式进行数据的交互，每次能够传输的最大数据量为 2^31 字节，因此使用 RocksDBStateBackend 合并的状态数据量大小不能超过 2^31 字节限制，否则将会导致状态数据无法同步。

#### 状态管理器配置

除了 MemoryStateBackend 不需要显示配置外，其他的状态管理器都需要进行相关的配置。Flink 提供了两种级别的状态管理器配置
- 应用层配置：配置的状态管理器只会针对当前应用有效
- 集群配置：对整个 Flink 进群上的所有应用有效

Flink 应用级别的状态管理器通过 StreamExecutionEnvironment 提供的 setStateBackend 方法配置
```scala
env = StreamExecutionEnvironment.getExecutionEnvironment()
env.setStateBackend(new FsStateBackend("hdfs://namenode:40010/flink/checkpoints"))
```

集群级别的状态管理器配置需要再 flink-conf.yaml 文件中通过参数 state.backend 指定状态管理器的类型，state.checkpoints.dir 配置状态存储路径
```
state.backend: filesystem
state.checkpoints.dir: hdfs://namenode:40010/flink/checkpoints
```
如果配置集群默认使用 RocksDBStateBackend 作为状态管理器，则再 flink-conf.yaml 中还需要配置相应的参数
```
# 指定同时可以操作 RocksDBStateBackend 的线程数
state.backend.rocksdb.checkpoint.transfer.thread.num: 1
# 指定 RocksDB 存储状态数据的本地文件路径，在每个 TaskManager 提供该路径存储节点中的状态数据
state.backend.rocksdb.localdir: /var/rockdb/flink/checkpoints
# 指定定时器服务实现的工厂类
state.backend.rocksdb.timer-server.factory: HEAP
```

### Queryable State

Flink 算子的状态作为流式数据计算的重要数据支撑，借助于状态可以完成相比无状态计算更加复杂的场景。Flink 提供了可查询的状态服务，也就是业务系统可以通过 Flink 提供的接口直接查询 Flink 系统内部的状态数据。

Flink 可查询状态服务包含三种重要组件：
- QueryableStateClient：用于外部应用中，作为客户端提交查询请求并收集状态查询结果
- QueryableStateClientProxy：用于接收和处理客户端的请求，每个 TaskManager 上运行一个客户端代理，状态数据分布在算子所有并发的实例中，ClientProxy 需要通过 JobMananger 中获取 key Group 的分布，然后判断哪个 TaskManager 实例维护了 Client 传过来的 key 对应的状态数据，并且向所在的 TaskMananger 的 Server 发送请求并将查询到的状态值返回给客户端
- QueryableStateServer：用于接收 ClientProxy 的请求，每个 TaskManager 上会运行一个 StateServer，该 Server 用于通过本地的状态后台管理器中查询状态结果，然后返回给客户端代理

为了能够开启可查询状态服务，需要在 Flink 中引入 flink-queryable-state-runtime.jar 文件，在 Flink 集群启动时就会自动加载到 TaskManager 被启动，此时就能够处理 Client 发送的请求。

除了在集群层面开启可查询服务，Flink 应用中也需要修改代码暴露可查询状态，在 StateDescriptor 中调用 setQueryable 方法就能够将需要暴露的状态开放。
```scala
override def open(parameters: Configuration): Unit = {
  val leastValueStateDescriptor = new ValueStateDescriptor[Long]("leastVlaue", classOf[Long])

  // 暴露可查询状态
  leastValueStateDescriptor.setQueryable("leastQueryValue")
  leastValueState = getRuntimeContext.getState(leastValueStateDescriptor)
}
```
Flink 同时提供了在 DataStream 数据集上类配置可查询的状态数据
```scala
val maxInputStream = inputStream
    .map(r => (r._1, r._2))
    .keyBy(_._1)
    .timeWindow(Time.seconds(5))
    .max(1)
maxInputStream.keyBy(_._1).asQueryableState("maxInputState")
```
通过在 KeyedStream 上使用 asQueryableState 方法来设定可查询状态，返回的 QueryableStateStream 将被当作 DataSink 算子，根据状态类型的不同，可以在 asQueryableState 方法中指定不同的 StateDescriptor 来设定相应的可查询状态
```scala
// ValueState
asQueryableState(String queryableStateNmae, ValueStateDescriptor stateDescriptor)

asQueryableState(String queryableStateNmae, ReducingStateDescriptor stateDescriptor)
```
开启可查询状态的应用执行的集群环境需要事先打开可查询状态服务，否则提交的应用将不能正常运行。

通过 QueryableStateClient 获取 Flink 应用的可查询状态数据，需要引入依赖
```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-core</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-queryable-state-client-java_2.11</artifactId>
</dependency>
```
QueryableStateClient 初始化参数分别为 TaskManager 的 Hostname 以及客户端代理监听的端口，默认时 9067，也可以在 /config/flink-conf.ymal 文件中配置
```scala
val tmHostname = "localhost"
val proxyPort = 9069
val jobId = "jobId"
val key = 5
val client = new QueryableStateClient(tmHostname, proxyPort)

// 创建需要查询的状态对应的 Descriptor
val valueDescriptor = new ValueStateDescriptor[Long]("leastValue", TypeInformation.of(new TypeHint[Long](){}))
// 查询状态的值
val resultFuture = client.getKvState(JobID.fromHexString(jobId), "leastQueryValue", key, Types.INT, valueDescriptor)
resultFuture.thenAccept(response => {
  try{
    val res = response.value()
    println(res)
  catch{
    case e: Exception => e.printStackTrace()
  }
}
})
```
### 重启策略
 Flink 提供了重启策略，RestartStrategies
 
 ```scala
env.setRestartStrategy(RestartStrategies.fixedDelayRestart(10, 1000L))
```

### 状态一致性

对于流处理器内部来说，所谓的状态一致性其实就是计算结果要保证正确，即使在遇到故障恢复后也应该保证计算结果的正确性。

端到端的状态一致性需要整个流处理应用的每个组件保证状态一致性，应用的一致性级别取决于组件中一致性最弱的组件。
- Flink 系统由 checkpoint 保证一致性
- source 需要能够重置数据的读取位置
- sink 需要保证不重复写入，即幂等写入或者事务写入

事务写入：等到 checkpoint 真正完成的时候才把所有对应的结果写入 sink 系统，通常有预写日志和二阶段提交两种方式
















