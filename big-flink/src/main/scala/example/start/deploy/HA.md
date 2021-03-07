## 高可用

Flink高可用配置中主要针对JobManager的高可用保证，因为JobManager是整个集群的管理节点，负责整个集群的任务调度和资源管理，如果JobManager出现问题将会导致新Job无法提交，并且已经执行的Job也将全部失败，因此对JobManager的高可用保证尤为重要。

### Standalone 集群高可用配置
Standalone集群中的JobManager高可用主要借助Zookeeper来完成，JobManager的服务信息会被注册到Zookeeper中，并通过Zookeeper完成JobManager Leader的选举。Standalone集群会同时存在多个JobManager，但只有一个处于工作状态，其他处于Standby状态，当Active JobManager失去连接后（如系统宕机）,Zookeeper会自动从Standby中选举新的JobManager来接管Flink集群。

在Standalone Cluster高可用配置中，需要对masters和flink-conf.yaml两个配置文件进行修改：
```yaml
# conf/flink-conf.yaml
high-availability: zookeeper
high-availability.zookeeper.quorum: zkHost1:2181, zkHost2:2181
high-availability.cluster-id: /standalone_cluster_1
high-availability.storageDir: hdfs://namenode.8020/flink/ha

# JobManager conf/master
localhost:8081
localhost:8082
```

### Yarn Session 高可用

在Flink Yarn Session集群模式下，高可用主要依赖于Yarn协助进行，主要因为Yarn本身对运行在Yarn上的应用具有一定的容错保证。

Flink OnYarn的模式其实是将Flink JobManager执行在ApplicationMaster所在的容器之中，同时Yarn不会启动多个JobManager达到高可用目的，而是通过重启的方式保证JobManager高可用。

通过配置yarn-site.xml文件中的yarn.resourcemanager.am.max-attempts参数来设定Hadoop Yarn中ApplicationMaster的最大重启次数。当Flink Session集群中的JobManager因为机器宕机或者重启等停止运行时，通过Yarn对ApplicationMaster的重启来恢复JobManager，以保证Flink集群高可用。

除了能够在Yarn集群中配置任务最大重启次数保证高可用之外，也可以在flink-conf.yaml中通过yarn.application-attempts参数配置应用的最大重启次数，在flink-conf. yaml中配置的次数不能超过Yarn集群中配置的最大重启次数。
```yaml
yarn.application-attempts: 5
```

## 安全管理



### 集群升级

与批计算场景不同，流式任务是7*24小时不间断运行，一旦任务启动，数据就会源源不断地接入到Flink系统中。如果在每次停止或重启Flink任务的过程中，不能及时保存原有任务中的状态数据，将会导致任务中的数据出现不一致的问题。为了解决此类问题Flink引入了Savepoint，它是Checkpoint的一种特殊实现，目的是在停止任务时，能够将任务中的状态数据落地到磁盘中并持久化，然后当重新拉起任务时，就能够从已经持久化的Savepoint数据中恢复原有任务中的数据，从而保证数据的一致性。

#### 任务重启

Flink的任务在停止或启动过程中，可以通过使用savepoint命令将Flink整个任务状态持久化到磁盘中。如以命令对正在执行的Flink任务进行savepoint操作，首先通过指定jobID确定需要重启的应用，然后通过指定Savepoint存储路径，将算子状态数据持久化到指定的本地路径中。

```shell
./bin/flink savepoint <jobId> [pathToSavepoint]
```

savepoint命令仅是将任务的状态数据持久化到固定路径中，任务并不会终止。可以通过如下cancel命令将正在运行的Flink任务优雅地停止，在停止过程中外部数据将不再接入，且Flink也不再执行Checkpoint操作，Savepoint中存储着当前任务中最新的一份Snapshot数据。

```shell
./bin/flink cancel -s [pathToSavepoint] <jobId>
```

#### 状态维护

Flink将计算逻辑构建在不同的Operator中，且Operator主要分为两种类型：一种为有状态算子，例如基于窗口计算的算子；另一种为无状态算子，例如常见的map转换算子等。默认情况下，Flink会对应用中的每个算子都会生成一个唯一的ID，当应用代码没有发生改变，任务重启就能够根据默认算子ID对算子中的数据进行恢复。但如果应用代码发生了比较大的修改，例如增加或者修改了算子的逻辑，将有可能导致算子中状态数据无法恢复的情况。针对这种情况，Flink允许在编写应用代码时对有状态的算子让用户手工指定唯一ID。

```scala
val mappendEvents = envents.map(new MyStatefulMapFunc()).uid("mapper-1")
```

通常情况下，并不是所有的算子都需要指定ID，用户可根据情况对部分重要的算子指定ID，其他算子可以使用系统分配的默认ID。同时Flink官方建议用户尽可能将所有算子进行手工标记，以便于在未来进行系统的升级和调整。另外对于新增加的算子，在Savepoint中没有维护对应的状态数据，Flink会将算子恢复到默认状态。

对于在升级过程中，如果应用接入的数据类型发生变化，也可能会导致有状态算子数据恢复失败。有状态算子从定义的形式上共分为两种类型：一种为用户自定义有状态算子，例如通过实现RichMapFunction函数定义的状态算子；另一种为Flink内部提供的有状态算子，例如窗口算子中的状态数据等。每种算子除了通过ID进行标记以外，当接入数据类型发生变化时，数据恢复策略也有所不同。

用户自定义状态算子一般是用户自定义RichFunction类型的函数，然后通过接口注册在计算拓扑中，函数中维系了自定义中间状态数据，在升级和维护过程中，需要用户对算子的状态进行兼容和适配。例如，如果算子状态数据的类型发生变化，则可以通过定义中间算子，间接地将旧算子状态转换为新的算子状态。

默认情况下，Flink算子内部的状态是不向用户开放的，这些有状态算子相对比较固定，对输入和输出数据有非常明确的限制。目前Flink内部算子不支持数据类型的更改，如果输入和输出数据类型发生改变，则可能会破坏算子状态的一致性，从而影响到应用的升级，因此用户在使用这类算子的过程中应尽可能避免输入和输出的数据类型发生变化。

#### 版本升级

在Flink集群版本进行升级的过程中，主要包含了两步操作，分别为：

- 执行Savepoint命令，将系统数据写入到Savepoint指定路径中。
- 升级Flink集群至新的版本，并重新启动集群，将任务从之前的Savepoint数据中恢复。

在升级操作中，用户可以根据不同的业务场景选择原地升级或卷影复制升级的策略。其中原地升级表示直接从原有版本的Flink集群位置升级，需要事先将集群停止，并替换新的集群安装包；而卷影复制不需要立即停止原有集群，首先将Savepoint数据获取下来，并在服务器其他位置重新搭建一套新的Flink集群，新的集群升级完毕后，通过在Savepoint数据中恢复，然后将原有Flink集群关闭。s















