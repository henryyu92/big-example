## Spark 安装启动

## 应用程序提交
Spark 在 Spark 的 bin 目录下提供 spark-submit 脚本用于提交应用程序。应用程序打包之后，使用 bin/spark-submit 脚本提交打包好的应用程序：
```shell
./bin/spark-submit \
--class <main-class> \
--master <master-url> \
--deploy-mode <deploy-mode> \
--conf <key>=<value> \
...
<application-jar> \
[application-arguments]
```
spark-submit 脚本支持多个可配置的参数，可以使用 spark-submit --help 查询：
- --class：应用程序的入口点，即 main 函数所在的类
- --master：Spark 集群的 master URL，如 spark://127.0.0.1:7077
- --deploy-mode：Driver 进程部署的位置，cluster 表示部署在 Worker 上，client 表示部署在客户端上
- --conf：Spark 配置属性，以 key=value 的形式指定，如果包含空格的话使用 "key=value"
- applicaiton-jar：包含依赖的应用程序 Jar 包的路径，URL 必须在集群内全局可见，如 hdfs://path 或者所有节点上都存在的 file://path
- application-arguments：传递给主方法的参数

当提交应用程序的机器和 worker 在相同的局域网内使用 client 模式比较合适，在 client 模式下，Driver 进程直接在提交应用程序时启动并且作为集群的客户端，应用程序的输入和输出打印到控制台;
当提交应用程序的机器和 worker 不在相同的局域网内则使用 cluster 模式比较合适，这样可以减少 driver 和 executor 之间的网络延迟。
### Master URL
spark 支持多种 Master URL，不同的 Mastr URL 代表不同的部署模式：

| Master URL | Meaning |
|:-:|:-:|
| local| 使用一个 worker 线程在本地运行 Spark，没有并行 |
| local[K]| 使用 K 个 worker 线程在本地运行 Spark，理想情况下设置为机器的核数 |
| local[K,F]| 使用 K 个工作线程和 F 个 maxFailures 在本地运行 Spark |
| local[*]|使用与计算机上的逻辑核心一样多的工作线程在本地运行 Spark |
| local[*,F]|使用与计算机上的逻辑核心一样多的工作线程和 F 个 maxFailures 在本地运行 Spark |
| spark://HOST:PORT | 连接到 Spark statndalone 集群的 Master，端口默认是 7077|
| spark://HOST1:PROT1,HOST2:PORT2 | 连接到使用了 ZooKeeper 设置了高可用的集群，列表包含所有 Master |
| mesos://HOST:PORT| 连接到指定的 Mesos 集群，端口默认是 5050。对于使用了 ZooKeeper 的 Mesos 集群，使用 mesos://zk://host:port， 使用 --deploy-mode cluster 提交|
| yarn| 以 client 还是 cluster 模式连接到 Yarn 集群取决于 --deploy-mode 的值；集群位置可以通过 HADOOP_CONF_DIR 或者 YARN_CONF_DIR 指定 |
| k8s://HOST:PORT| 以集群的模式连接 Kubernetes 集群，默认使用 TLS 连接，可以使用 k8s://http://HOST:PORT 来强制使用不安全的连接 |
## Spark RDD
RDD(Redilient Distributed Dataset) 是 Spark 提供的一个最主要的数据抽象，它是跨集群节点分区并且可以并行操作的数据的集合。 
### RDD 特征
RDD 作为 Spark 的数据抽象，有 5 个重要的内部属性：
- partitions_ : Array[Partition] 分区列表，每个分区的数据是可以并行计算的
- compute(split: Partition, context: TaskContext): Iterator[T]  计算每个分区的函数
- dependencies_ : Seq[Dependency[_]] 依赖列表
- partitioner: Option[Partitioner] K-V 类型的 RDD 的分区函数，可选
- 每个分区优先计算位置
### RDD 依赖
Spark RDD 的依赖可以分为窄依赖和宽依赖：
- 窄依赖是指父 RDD 的每一个分区最多被一个子 RDD 的分区所用；
- 宽依赖是指一个父 RDD 中的一个分区对应子 RDD 的多个分区，宽依赖是 Stage 划分的重要依据；

窄依赖的 RDD 可以通过相同的键进行联合分区，整个操作都可以在一个集群节点上进行，以流水线(pipeline)的方式计算所有的父分区不会造成网络之间的数据混合；宽依赖 RDD 首先需要计算好所有父分区数据然后在节点之间 shuffle。
### RDD 创建
RDD 除了可以从父 RDD 转化还支持两种方式创建 RDD：
- 并行化集合创建 RDD
- 从支持 Hadoop 输入格式数据源的外部存储系统创建 RDD
#### 集合创建 RDD
通过调用```sparkContext#parallelize```方法可以创建一个 RDD，一旦 RDD 创建完成就不可变。
```scala
def parallelize[T: ClassTag](seq: Seq[T], numSlices: Int = defaultParallelism): RDD[T]
```
其中 seq 表示 Scala 集合，numSlices 表示分区数，在集群模式中 Spark 将会在每个分区上运行一个 Task
```java
val sc = SparkSession.builder.appName("App Name").master("local[*]").getOrCreate.sparkContext
try{
  sc.parallelize(Array("test", "hello"), 4)
}finally{
  sc.stop
}
```
#### 存储创建 RDD
Spark 可以从本地文件创建也可以从由 Hadoop 支持的文件系统和 Hadoop 支持的输入格式创建分布式数据集
```scala
def textFile(path: String, minPartitions: Int = defaultMinPartitions): RDD[String]

def wholeTextFiles(path: String, minPartitions: Int = defaultMinPartitions): RDD[(String, String)]

def newAPIHadoopFile[K, V, F <: NewInputFormat[K, V]](
      path: String,
      fClass: Class[F],
      kClass: Class[K],
      vClass: Class[V],
      conf: Configuration = hadoopConfiguration): RDD[(K, V)]
```
```java
val sc = SparkSession.builder.appName("APP Name").master("local[*]").getOrCreate.sparkContext
try{
    sc.textFile("file/path", 4);
}finally{
    sc.stop();
}
```
### RDD Transformation
操作从已经存在的数据集上创建一个新的数据集，是数据集的逻辑操作。Transformation 操作并没有真正计算只是标记对 RDD 的操作。

#### SparkContext 创建
Spark 提供了 SparkSession 用于创建 SparkContext：
```scala
val sc = SparkSession.builder
.appName("Spark Map Transformation")
.master("local[*]")
.getOrCreate.sparkContext
```

#### RDD 操作
- ```def map[U: ClassTag](f: T => U): RDD[U]``` 将源 RDD 的每个元素通过 map 中的自定义函数转变为新的元素
- ```def flatMap[U: ClassTag](f: T => TraversableOnce[U]): RDD[U]``` 将源 RDD 的每个元素通过自定义函数转变为新的元素并将生成的 RDD 的每个集合中的元素合并为一个集合

  ```scala
  val rdd = sc.parallelize(List("spark", "scala", "java", "flink"), 4)
  // map 对集合中的每个元素作用于 f 函数，函数返回与元素相同的数据类型
  rdd.map(_ + "_suffix").collect.foreach(println)
  // flatMap 对集合中的每个元素作用域 f 函数，函数返回集合数据类型
  rdd.flatMap(x => Array(x + "_suffix")).collect.foreach(println)
  ```
- ```def mapPartitions[U: ClassTag](f: Iterator[T] => Iterator[U],preservesPartitioning: Boolean = false): RDD[U]``` 获取每个分区的迭代器，在函数中通过这个分区迭代器对整个分区的元素进行操作
  
  ```scala
  sc.parallelize(Array("java", "scala", "spark", "flink"), 3)
    .mapPartitionsWithIndex((index, partition) =>{
      partition.map(_ + "_suffix_" + index)
    }).collect.foreach(println)
  ```
- ```def glom(): RDD[Array[T]]``` 将每个分区的元素合并为数组
  
  ```scala
  sc.parallelize(List("java", "scala", "spark", "flink"), 4).glom().foreach(_.foreach(println))
  ```
- ```def union(other: RDD[T]): RDD[T]``` 将两个 RDD 做并集运算(不做去重处理)，两个 RDD 中的数据类型需要相同
- ```def cartesian[U: ClassTag](other: RDD[U]): RDD[(T, U)]``` 将两个 RDD 内的所有元素进行笛卡尔积操作
  ```scala
  val l = sc.parallelize(List("java", "scala", "go"))
  val p = sc.parallelize(List("flink", "spark", "kubernetes"))
  // union 是将两个 RDD 合并成一个 RDD，数据类型不会变化
  l.union(p).foreach(println)
  // 笛卡尔积是将两个 RDD 中的数据进行组合，数据类型变为元组
  l.cartesian(p).foreach(println)
  ```
- ```def groupBy[K](f: T => K, p: Partitioner)(implicit kt: ClassTag[K], ord: Ordering[K] = null): RDD[(K, Iterable[T])]``` 将元素通过函数生成相应的 key，然后将 key 相同的元素分为一组
  ```scala
  sc.parallelize(List(("java", "flink"), ("scala", "flink"), ("java", "hbase"), ("go", "docker"), ("go", "kubernetes")))
    .groupByKey.foreach(x => println(x._1 + ": " + x._2.mkString(",")))
  ```
- ```def filter(f: T => Boolean): RDD[T]``` 对 RDD 的元素过滤，过滤函数返回 true 的元素保留
- ```def distinct(): RDD[T]``` 将 RDD 中的每个元素进行去重操作，x先在每个分区进行去重然后汇总后再去重
  ```scala
  val d = sc.parallelize(List(1, 2, 3, 4, 5, 4, 3, 2, 1))
  // filter 只保留满足条件的元素
  d.filter(_ % 2 == 0).foreach(x => println("filter_" + x))
  // distinct 是对元素去重，相当于 filter 的特殊情况
  d.distinct(4).foreach(x => println("distinct_" + x))
  ```
- ```def subtract(other: RDD[T]): RDD[T]``` 保留当前 RDD 存在，other RDD 中不存在的元素
  ```scala
  // 计算两个集合的差集，集合中的元素数据类型需要保持一致
  d.distinct(4).subtract(d.filter(_ % 2 == 0)).foreach(println)
  ```
- ```def sample(withReplacement: Boolean,fraction: Double,seed: Long = Utils.random.nextLong): RDD[T]``` 对 RDD 中的元素进行抽样
- ```def takeSample(withReplacement: Boolean,num: Int,seed: Long = Utils.random.nextLong): Array[T]``` 对 RDD 中的元素进行指定数量的抽样，这个动作是立即执行并抽样结果直接返回 Driver
  ```scala
  // fraction 表示抽样的比例，sample 返回的是 RDD 是懒执行的，foreach 是 RDD 的执行动作
  sc.parallelize(List(1, 3, 2, 4, 6, 7)).sample(false, 0.5).foreach(println)
  // fraction 表示抽样的数量，takeSample 返回 Array 是立即执行的，foreach 是 Scala 集合操作
  sc.parallelize(List(1, 3, 2, 4, 6, 7)).takeSample(false, 4).foreach(println)
  ```

#### PairRDDFunctions 操作
- ```def mapValues[U](f: V => U): RDD[(K, U)]``` 对 (key, value) 类型 RDD 中的元素的 value 进行 map 操作，而不对 key 进行操作
  ```scala
  sc.parallelize(List(("java", "flink"), ("scala", "spark"), ("go", "kubernetes"))).mapValues(_ + "_suffix").foreach(println)
  ```
- ```def combineByKey[C](createCombiner: V => C, mergeValue: (C, V) => C, mergeCombiners: (C, C) => C): RDD[(K, C)]``` createCombiner 表示 C 不存在时创建 C（同一分片），mergeValue 表示 C 存在时将 V 添加到 C（同一分片），mergeCombiners 表示将两个 C 合并(不同分片间合并)
- ```def reduceByKey(partitioner: Partitioner, func: (V, V) => V): RDD[(K, V)]``` 合并 RDD 中的值，底层使用 combineByKey，其中 mergeValue 和 mergeCombiners 的逻辑相同
- ```def groupByKey(): RDD[(K, Iterable[V])]``` 根据 key 聚合 RDD 中的元素
  ```scala
  val rdd = sc.parallelize(List("spark", "scala", "java", "flink"), 4).map((_, 1))
    
  rdd.combineByKey(List(_), (x: List[Int], y: Int) => y :: x, (x: List[Int], y: List[Int]) => x ::: y).foreach(println)

  rdd.reduceByKey(_ + _).foreach(println)

  rdd.groupByKey().foreach(println)
  ```
- ```def partitionBy(partitioner: Partitioner): RDD[(K, V)]``` 使用自定义分区器对 RDD 进行重分区
- ```def cogroup[W](other: RDD[(K, W)]): RDD[(K, (Iterable[V], Iterable[W]))]``` 每个 RDD 相同的 key 聚合为一个集合，然后将两个 RDD 中 key 相同的集合迭代器再聚合为元组
  ```scala
  
  ```
- ```def join[W](other: RDD[(K, W)], partitioner: Partitioner): RDD[(K, (V, W))]``` 对两个 RDD 做 join 操作，底层采用 cogroup 操作之后，对每个 key 下的元素进行笛卡尔积操作然后再 flatMap
- ```def leftOuterJoin[W](other: RDD[(K, W)],partitioner: Partitioner): RDD[(K, (V, Option[W]))]``` 对两个 RDD 做 leftJoin 操作，如果另一侧元素为空，则用空填充
  ```scala
  
  ```
### RDD Action
Spark Action 算子会通过 SparkContext 执行提交任务的方法```runJob()``` 从而触发 RDD DAG 的执行：
- ```def foreach(f: T => Unit)``` 在每个 RDD 元素上调用函数并提交任务
- ```def collect(): Array[T]``` 将分布式的 RDD 返回为一个单机的 Array
- ```def collectAsMap(): Map[K, V]``` 对 (K,V) 型的 RDD 的数据返回一个单机的 HashMap，对于重复 K 的 RDD 元素，后面的元素覆盖前面的元素
- ```def reduceByKeyLocally(func: (V, V) => V): Map[K, V]``` 先对 RDD 做 reduce 操作，然后再收集所有结果返回一个 HashMap，和先 reduce 再 collectAsMap 效果相同
  ```scala
  ```
- ```def lookup(key: K): Seq[V]``` 对 (K,V) 类型的 RDD 操作，返回指定 Key 对应的元素；如果 RDD 包含分区器则只会处理 K 对应的分区，否则扫描所有的分区搜索 K 对应的元素
  ```scala
  ```
- ```def count(): Long``` 计算 RDD 中元素的个数
- ```def top(num: Int)(implicit ord: Ordering[T]): Array[T]``` 获取 RDD 中最大的 k 个元素，RDD 的所有数据都在 Driver 上
- ```def max()(implicit ord: Ordering[T]): T``` 返回 RDD 中最大的一个元素，相当于 top(1)
- ```def takeOrdered(num: Int)(implicit ord: Ordering[T]): Array[T]``` 获取 RDD 中最小的 k 个元素
- ```def take(num: Int): Array[T]``` 获取 RDD 中的前 k 个元素，这种方法先扫描一个分区获取 num 个元素然后扫描其他分区保证 num 个元素满足条件，需要将所有的数据加载到 Driver 中
- ```def first(): T``` 获取 RDD 中的第一个元素，相当于 take(1)
  ```scala
  ```
- ```def reduce(f: (T, T) => T): T``` 对当前 RDD 的元素做 reduce 操作，先对每个分区做 reduce 操作然后将分区结果做 reduce 操作
- ```def fold(zeroValue: T)(op: (T, T) => T): T``` 和 reduce 类似，但是在做 reduce 操作时有一个零值
  
  ```scala
  val sc = SparkSession.builder.appName("Spark Action").master("local[*]").getOrCreate().sparkContext
  val rdd = sc.parallelize(Array(1,2,3,4,5,6,6,7), 2)
  // 34
  println(rdd.reduce((x, y) => x + y))
  // 64
  println(rdd.fold(10)((x, y) => x + y))
  ```
- ```def aggregate[U: ClassTag](zeroValue: U)(seqOp: (U, T) => U, combOp: (U, U) => U): U```  reduce 和 fold 的初始值和返回值必须和 RDD 类型一致，但是 aggregate 可以不一致
  
  ```scala
  // 计算均值
  val r = rdd.aggregate(0, 0)((x, y) => (x._1 + y, x._2 + 1), (x, y)=>(x._1 + y._1, x._2 + y._2))
  println(r._1 / r._2.toFloat)
  ```
### RDD 输出
- ```def saveAsTextFile(path: String): Unit``` 将数据输出到指定目录
- ```def saveAsObjectFile(path: String): Unit``` 将分区中的每 10 个元素组成一个 Array，然后将这个 Array 序列化写入 HDFS 为 SequenceFile 格式
### RDD 持久化
- def cache() 将 RDD 元素缓存在内存
- def persist(newLevel: StorageLevel): this.type 将 RDD 中的元素进行缓存，可以指定缓存级别
```scala

```
### 广播变量
广播(broadcast)变量用于广播 Map Side Join 中的小表以及广播大变量，这些数据集合单个节点内存能够容纳，不需要存储在多个节点；Spark 运行时把广播变量数据发到各个节点并保存下来，后续计算可以复用。
accumulator 变量允许全局累加操作，广泛应用于记录当前的运行指标的场景
### 检查点
Spark 提供检查点和记录日志用于持久化中间 RDD，使用 RDD 的 checkpoint 方法启动检查点功能。



## Spark 基础组件
### Spark 配置
Spark 的配置在 SparkConf 中，使用 ConcurrentHashMap 作为配置的存储数据结构：
```scala
private val settings = new ConcurrentHashMap[String, String]()
```
Spark 的配置来源有 3 处：
- 系统参数(System.getProperties 获取的属性)中以 spark. 作为前缀的属性
- 使用 ```SparkConf#set``` 方法设置
- 从其他 SparkConf 克隆
### Spark RPC
Spark RPC 基于 Netty 实现，由多个组件构成：
- TransportContext：传输上下文，包含了用于创建传输服务端(TransportServer)和传输客户端工厂(TransportClientFactory)的上下文信息
- TransportConf：传输上下文配置
- RpcHandler：对调用传输客户端(TransportClient)的 sendRpc 方法发送的消息的处理
- MessageEncoder：在将消息放入 pipeline 前对消息进行编码防止半包问题
- MessageDecoder：对从 pipeline 读取的 ByteBuf 进行解析防止丢包或解析错误
- TransportFrameDecoder：对从 pipeline 读取的 ByteBuf 按照数据帧进行解析
- RpcResponseCallback：RpcHandler 处理完请求消息后的回调接口
- TransportClientFactory：创建 TransportClient 的工具类，通过 TransportContext#createClientFactory 创建
- ClientPool：在两个对等节点间维护的关于 TransportClient 的池子
- TransportClient：RPC 框架的客户端，用于处理数据流中的连续块
- TransportClientBootstrap：创建客户端的引导类
- TransportRequestHandler：用于处理客户端的请求并在写完数据块后返回的处理
- TransportChannelHandler：代理由 TransportRequestHandler 处理的请求和 TransportResponseHadnler 处理的响应，并加入传输层的处理
- TransportServerBootstrap：创建服务端的引导类
- TransportServer：RPC 框架的服务端

#### TransportConf
TransportConf 用于给 TransportContext 提供配置信息，有两个成员属性：
```scala
// 配置提供者
private final ConfigProvider conf;
// 配置的模块名称
private final String module;
```
Spark 通常使用 SparkTransportConf 的 fromSparkConf 方法创建 TransportConf，该方法有三个参数：
- _sparkConf 是 Spark 的配置类，用于将配置信息 clone 到 TransportConf 中
- module 是配置的模块
- numUsableCores 配置 client 端及 server 端用于网络传输的线程数，默认是系统的核心数。由参数 ```spark.$module.io.serverThreads``` 和 ```spark.$module.io.clientThreads``` 设置
```scala
  def fromSparkConf(_conf: SparkConf, module: String, numUsableCores: Int = 0): TransportConf = {
    val conf = _conf.clone

    // Specify thread configuration based on our JVM's allocation of cores (rather than necessarily
    // assuming we have all the machine's cores).
    // NB: Only set if serverThreads/clientThreads not already set.
    val numThreads = defaultNumThreads(numUsableCores)
    conf.setIfMissing(s"spark.$module.io.serverThreads", numThreads.toString)
    conf.setIfMissing(s"spark.$module.io.clientThreads", numThreads.toString)

    new TransportConf(module, new ConfigProvider {
      override def get(name: String): String = conf.get(name)
      override def get(name: String, defaultValue: String): String = conf.get(name, defaultValue)
      override def getAll(): java.lang.Iterable[java.util.Map.Entry[String, String]] = {
        conf.getAll.toMap.asJava.entrySet()
      }
    })
  }
  
```
#### RPC 客户端
##### TransportClientFactory
TransportClientFactory 是创建 TransportClient 的工厂类，通过 ```TransportContext#createClientFactory``` 创建：
```scala
  public TransportClientFactory createClientFactory(List<TransportClientBootstrap> bootstraps) {
    return new TransportClientFactory(this, bootstraps);
  }
```
##### TransportClientBootstrap
TransportClientFactory 中包含 TransportClientBootstrap 列表属性，主要负责客户端建立连接时进行一些初始化的准备。TransportClientBootstrap 有两个实现类 AuthClientBootstrap 和 SaslClientBootstrap 分别用于创建连接时的认证和 SSL 校验。
##### TransportClient
TransportClient 是 Spark RPC 的客户端，每一个 TransportClient 实例只能和一个远端 RPC 服务通信，如果需要和多个 RPC 服务通信则需要创建多个实例。

TransportClientFactory 在维护了一个客户端连接池用于复用已经创建的连接，如果
### 事件总线
### 度量系统
## SparkContext
SparkContext 的组成部分：
- SparkEnv：Spark 运行时环境，Executor 的执行依赖 SparkEnv 提供的运行时环境
- LiveListenerBus：事件总线，可以接收各个使用方的事件并通过异步方式对事件进行匹配后调用 SparkListener 的不同方法
- SparkUI：Spark 的用户界面，作业(Job)、阶段(Stage)、存储、执行器(Executor) 等组件的监控数据都会以 SparkListenerEvent 的形式投递到 LiveListenerBus 中，SparkUI 将从各个 SparkListener 中读取数据并显示到 Web 界面
- SparkStatusTracker：提供对 Job、Stage 等的监控信息，只能提供非常脆弱的一致性
- ConsoleProgressBar：利用 SparkStatusTracker 的 API 在控制台上显示 Stage 的进度，存在一定的延时
- DAGScheduler：DAG 调度器，负责 Stage 的切分和 Task 的创建并提交 Task。SparkUI 中有关 Job 和 Stage 的监控数据都来自 DAGScheduler
- TaskScheduler：Spark 的任务调度器，按照调度算法对集群管理器(Cluster Manager)已经分配给应用程序的资源进行二次调度后分配给 Task，TaskScheduler 调度的 Task 是由 DAGScheduler 创建的。
- HeartbeatReceiver：心跳接收器，所有的 Executor 都会向 HeartbeatReceiver 发送心跳信息，HeartbeatReceiver 接收到心跳信息之后首先更新 Executor 的最后可见时间然后将此信息交给 TaskScheduler 作进一步处理
- ContextCleaner：上下位清理器，采用异步方式清理那些超出应用作用域范围的 RDD、ShuffleDependency 和 Broadcast 等信息
- JobProgressListener：作业进度监听器，JobProgressListener 将注册到 LiveListenerBus 中作为事件监听器
- EventLoggingListener：将事件持久化到存储的监听器，当 ```spark.eventLog.enabled``` 设置为 true 时启用
- ExecutorAllocationManager：根据工作负载动态调整 Executor 的数量，```spark.dynamicAllocation.enabled``` 设置为 true 并且 ```spark.dynamicAllocation.testing``` 设置为 true 或者 ```spark.master``` 不是 local 模式时启用
- ShutdownHookManager：用于设置关闭钩子的管理器，可以设置在 JVM 关闭时处理清理工作的钩子程序

#### 创建 Spark 环境
SparkEnv 是 Spark 执行任务时的环境。在生产环境中，SparkEnv 运行于不同节点的 Executor 中，在 local 模式下 Dirver 中也需要 SparkEnv。SparkEnv 在 SparkContext 初始化时创建：
```scala
```
#### SparkUI 实现


## Spark 任务调度
Spark 任务调度是 Spark core 的核心模块，任务调度模块主要分为两部分：DAGScheduler 和 TaskScheduler，负责将用户提交的计算任务按照 DAG 划分为不同的阶段并将不同阶段的计算任务提交到集群进行最终的计算。




Spark 任务的调度涉及到的最重要的三个类是：```DAGScheduler```、```TaskScheduler``` 和 ```SchedulerBackend```；```SchedulerBackend``` 是一个 trait，作用是分配当前可用的资源，具体就是向当前等待分配计算资源的 Task 分配计算资源(Executor)并且在分配的 Executor 上启动 Task 完成计算的调度过程；```TaskScheduler```也是一个 trait，作用是为创建它的 ```SparkContext``` 调度任务，即从 ```DAGScheduler``` 接收不同 Stage 的任务并且向集群提交这些任务，```TaskScheduler``` 会在以下几种场景下调用 ```SchedulerBackend#reviveOffers``` 方法：
- 有新任务提交时
- 有任务执行失败时
- 计算节点(Executor)不可用时
- 某些任务执行过慢而需要为它重新分配资源时
### DAGScheduler
DAGScheduler 主要负责分析用户提交的应用，并根据计算任务的依赖关系构建 DAG，然后将 DAG 划分为不同的 Stage，每个 Stage 都是由一组可以并发执行的 Task 构成，这些 Task 的执行逻辑完全一样只是作用于不同的数据；DAG 在不同的资源管理框架(statndalone、yarn、mesos等)下实现是相同的。


#### DAGScheduler 的创建
DAGScheduler 在 SparkContext 创建的时候创建的：
```scala
_dagScheduler = new DAGScheduler(this)

def this(sc: SparkContext) = this(sc, sc.taskScheduler)

private[spark] class DAGScheduler(
    private[scheduler] val sc: SparkContext,
    private[scheduler] val taskScheduler: TaskScheduler,
    listenerBus: LiveListenerBus,
    mapOutputTracker: MapOutputTrackerMaster,
    blockManagerMaster: BlockManagerMaster,
    env: SparkEnv,
    clock: Clock = new SystemClock())
  extends Logging {
```
其中 MapOutputTrackerMaster 是运行在 Driver 端管理 Shuffle Map Task 的输出的，BlockManagerMaster 也是运行在 Driver 端管理整个 Job 的 Block 信息。
#### Job 提交
RDD 的 Action 算子会触发任务提交，最终会调用 ```DAGScheduler#runJob```，进而调用 ```DAGScheduler#submitJob``` 方法
可以看到首先会为 Job 生成一个 JobId，然后创建一个 JobWaiter 的实例监听 Job 的执行情况，最终调用 ```eventProcessLoop#post``` 方法提交任务

#### Stage 划分
```eventProcessLoop#post``` 提交任务后最终由```DAGSchedulerEventProcessLoop#onReceive``` 方法来处理提交的 Job，该方法会使用```dagScheduler#handleJobSubmitted``` 来处理任务的提交。

首先会根据 RDD 创建 finalStage，然后创建 ActiveJob 后提交计算任务。用户提交的计算任务是一个由 RDD 构成的 DAG，如果 RDD 在转换的时候需要 Shuffle，那么这个 Shuffle 的过程就将这个 DAG 分为了不同阶段(Stage)。由于 Shuffle 的存在，不同的 Stage 是不能并行计算的，因为后面的 Stage 依赖前面 Stage 的计算结果。

对于窄依赖，RDD 每个 partition 依赖固定数量的 parentRDD 的 partition，因此可以通过一个 Task 来处理这些 partition，而且这些 partition 相互独立因此可以并行执行；对于宽依赖，由于需要shuffle，因此只有所有的父 RDD 的 partition shuffle 完成新的 partition 才能形成，才能执行接下来的 Task。

### Task 的生成
new ActiveJob 完成 Job 的生成

### TaskScheduler
DAGScheduler 将任务划分为多个 Stage 之后会将每个 Stage 的 Task 提交到 TaskScheduler，TaskScheduler 通过 ClusterManager 在集群中的某个 Worker 的 Executor 上启动任务。在 Executor 中运行的任务如果缓存中没有计算结果那么就需要开始计算，同时把计算结果回传到 Driver 或者保存在本地；在不同的资源管理框架下，TaskScheduler 的实现是不同的，```TaskSchedulerImpl```是在 Local、Standalone、Mesos 资源管理器的实现，而```TaskSchedulerImpl``` 的子类```YarnClientClusterScheduler``` 和 ```YarnClusterScheduler``` 分别是在 Yarn Client 和 Yarn Cluster 资源管理器的实现。



#### TaskScheduler 的创建
TaskScheduler 在 SparkContext 创建的时候通过 ```SparkContext.createTaskScheduler(this, master, deployMode)```创建；createTaskScheduler 会根据传入的 Master 的 URL 的规则判断不同的部署方式，根据不同的部署方式创建不同的 TaskScheduler 和SchedulerBackend 实现。

通过实现 ```ExternalClusterManager``` trait 可以自定义 TaskScheduler 和对应的 SchedulerBackend，参考 YarnClusterManager
#### Task 的提交
Task 的提交有 ```TaskScheduler#submitTask``` 方法完成；DAGScheduler 完成 Stage 划分后会按照顺序逐个将 Stage(TaskSet) 提交到 TaskScheduler，TaskScheduler 将 TaskSet 加入到 TaskSetMananger 中，TaskSetManager 根据就近原则为 Task 分配资源，监控 Task 的执行状态
#### Task 的调度
1. FIFO 调度
采用 FIFO 调度保证 JobId 较小的先被调度，如果是同一个 Job 则 StageId 较小的先被调度
2. Fair 调度
### SchedulerBackend
每个 TaskScheduler 对应一个 SchedulerBackend；TaskScheduler 负责 Application 的不同 Job 之间的调度，在 Task 执行失败时启动重试机制并且为执行速度慢的 Task 启动备份的任务；SchedulerBackend 负责与 Cluster Manager 交互取的该 Application 分配到的资源并将这些资源传给 TaskScheduler 用于最终分配计算资源





## Spark 任务执行
### Executor
## Spark Shuffle
## Spark 存储
## Spark 部署
### Standalone
### Yarn
### Mesos
### Kubernetes
## 集群容错
### Master 容错
Master 异常退出后，新的任务不能再提交了而老的计算任务仍然可以继续进行，但是部分功能如资源回收则不能使用，因此 Master 需要有容错能力。

Master 可以集群部署并使用 ZooKeeper 的选举机制保证 Master 的主从备份，当 Master 主节点异常时就在从节点中选出一个作为主节点，新选出来的主节点首先从 ZooKeeper 中读取集群的元数据(Workker、Driver Client 和 Application 的信息)进行数据恢复，然后告知 Woker 和 AppClient 更改消息，当接受到所有 Woker 和 AppClient 的响应或者超时后，该节点就会将自己置为 ACTIVE 状态并对外提供服务。
### Worker 容错
Worker 异常退出时会将所有运行在它上面的 Executor 和 Driver Client 删除；由于 Worker 异常退出导致在心跳周期(spark.worker.timeout 的 1/4)内不能和 Master 通信，Master 会认为该 Worker 异常退出并将该 Worker 上运行的所有 Executor 的状态标记为丢失(ExecutorState.LOST)，然后将这个状态更新通过消息 ExecutorUpdate 通知 AppClient；对于 Worker 上运行的 Driver Client 如果设置了重启则需要重启这个 Driver Client，否则直接删除并将状态设置为 DriverState.ERROR；
### Executor 容错
Executor 负责运行 Task 计算任务并将计算结果回传到 Driver，Spark 的资源调度框架在为计算任务分配了资源后都会使用 Executor 模块完成最终的计算。

Worker 在接收到 Master 的 LauchExecutor 命令后会创建 ExecutorRunner，通过 ExecutorRunner#fetchAndRunExecutor 方法向 Driver 注册 Executor，Driver 在接收到 Executor 的 RegisterExecutor 消息后将 Executor 的信息保存在本地然后调用 CoarseGrainedSchedulerBackend.DriverActor#makeOffers 实现 Executor 上启动 Task

## Spark 性能调优

## Spark SQL
### DataFrame 和 DataSet
DataFrame 是以指定列组织的分布式数据集合，相当于数据库的一个表带有 RDD 的 schema 信息；DataSet 是 DataFrame 更高层次的抽象，DataFrame 相当于 DataSet[Row]。
#### 创建 DataFrame
```scala
val spark = SparkSession.builder
      .appName("Spark DataFrame")
      .master("local[2]")
      .getOrCreate()

import spark.implicits._
// 通过读取 json 文件创建 DataFrame
val df = spark.read.json("/path/of/json/data")

// 打印 DataFrame 的 Schema
df.printSchema()
```
#### DataFramme 操作
- 算子操作：DataFrame 支持 DSL 的语法格式
  ```scala
  df.select($"age", $"age" + 10).show
  df.filter($"age" < 10).show
  df.agg("age"->"max", "age"->"avg", "name"->"count").show
  ```
- sql 操作：DataFrame sql 操作需要先将 DataFrame 注册为一个 Temp View，然后直接通过 ```SparkSession.sql()``` 方法使用 SQL 语法操作。将 DataFrame 注册为 Temp View 有 ```DataFrame#createOrReplaceTempView()``` 和 ```createOrReplaceGlobalTempView()``` 两种方式，分别表示 Session 有效和全局有效，其中全局 Temp View 使用时需要指定库名 global_temp。
  ```scala
  df.createOrReplaceTempView("people")
  spark.sql("select * from people where age > 10").show

  // 需要指定 global_temp 库
  f.createOrReplaceGlobalTempView("person")
  spark.sql("select * from global_temp.person where age > 10").show
  ```
#### DataSet 创建
```scala
import spark.implicits._
val ds = List(1, 2, 3, 4).toDS

ase class Person(name: String, age:Int)
spark.read.json("/data/text.json").as[Person].show
```
#### RDD 转换为 DataSet
- 通过反射：Spark SQL 支持自动将 包含 case class 的 RDD 转换为 DataFrame
  ```scala
  val spark = SparkSession.builder
    .appName("RDD2DataSet")
    .master("local[2]")
    .getOrCreate()
  val sc = spark.sparkContext

  case class Person(name: String, age:Int)
  import spark.implicits._
  val df = sc.textFile("/data/text.text")
    .map(_.split(","))
    .map(attr =>Person(attr(0), attr(1).toInt))
    .toDF

  df.createOrReplaceTempView("person")
  spark.sql("select * from person where age > 10")
  ```
- 手动指定 schema：当 case class 不能提前定义时，需要手动指定 schema
  ```scala
  // 创建 RDD
  val rdd = spark.sparkContext.textFile("data/person.txt")
  // schema
  val schemaString = "name age"
  // 创建 schema
  val fields = schemaString.split(" ")
    .map(fieldName => StructField(fieldName, StringType, nullable = true))
  val schema = StructType(fields)
  // RDD 转换为 Row
  val rowRdd = rdd.map(_.split(" ")).map(attr => Row(attr(0), attr(1).trim))
  // 将 schema 应用于 RDD
  val df = spark.createDataFrame(rowRdd, schema)

  df.createOrReplaceTempView("person")
  spark.sql("select * from person").show
  ```
#### 自定义聚合函数
### 数据源
Spark 支持通过 DataFrame 接口操作多种不同的数据源。
#### 加载保存操作
DataFrame 数据源的默认是 parquet 格式，可以使用 spark.sql.source.default 修改默认的数据格式：
```scala
val spark = SparkSession.builder
  .appName("DataFrame Source")
  .master("local[*]")
  .config("spark.sql.sources.default", "json")
  .getOrCreate()

val df = spark.read.load("data/person.json")
    
df.createOrReplaceTempView("person")
spark.sql("select * from person").show

spark.stop()
```
Spark SQL 支持通过在 fromat() 中指定数据源格式的全限定名指定数据源类型另外还支持 option() 指定数据源的附加选项：
```scala
val peopleDF = spark.read.format("json").load("data/people.json")
peopleDF.select("name", "age").write.format("parquet").save("person.parquet")

val peopleDFCsv = spark.read.format("csv")
  .option("sep", ";")
  .option("inferSchema", "true")
  .option("header", "true")
  .load("data/people.csv")
  
usersDF.write.format("orc")
  .option("orc.bloom.filter.columns", "favorite_color")
  .option("orc.dictionary.key.threshold", "1.0")
  .save("users_with_options.orc")
```
除了将数据加载成 DataFrame 然后执行查询，也可以直接在数据源上执行 SQL 查询：
```scala
spark.sql("select name, age + 1 as age from json.`data/person.json`").show
```
#### Hive 表
Spark SQL 支持从 Hive 读写数据，当使用 Hive 的时候 Hive 相关的配置 hive-site.xml，core-site.xml 和 hdfs-site.xml 放置到 conf 目录下，并且需要 SparkSession 添加 Hive 支持：
```scala
val spark = SparkSession.builder
  .appName("Hive Table")
  .config("spark.sql.warehouse.dir", "/warehouse/dir")
  .enableHiveSupport()
  .getOrCreate()

// 使用 spark SQL 原生语法创建 Hive 表
spark.sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING) USING hive")
spark.sql("LOAD DATA LOCAL INPATH 'data/person.txt' INTO TABLE src")
// 直接使用 HiveSql 查询而不需要注册为临时表
spark.sql("select * from src").show
spark.sql("SELECT COUNT(*) FROM src").show()
```
SQL 执行的结果数据是 DataFrame，可以支持 DataFrame 的所有函数，DataFrame 中每个元素都是 Row 类型：
```scala
val sqlDF = spark.sql("SELECT key, value FROM src WHERE key < 10 ORDER BY key")
sqlDF.map {
  case Row(key: Int, value: String) => s"Key: $key, Value: $value"
}.show()
```
DataFrame 注册的临时表也可以和 Hive 管理的表一起操作并且 DataFrame 可以保存为 Hive 管理的表：
```scala
// 创建 DataFrame 并注册为临时表
val recordsDF = spark.createDataFrame((1 to 100).map(i => Record(i, s"val_$i")))
recordsDF.createOrReplaceTempView("records")

spark.sql("SELECT * FROM records r JOIN src s ON r.key = s.key").show()

// 使用 HiveSQL 创建表
spark.sql("CREATE TABLE hive_records(key int, value string) STORED AS PARQUET")

// 将 DataFrame 持久化为 Hive 管理的表
spark.table("src").write.mode(SaveMode.Overwrite).saveAsTable("hive_records")
```
#### JDBC
使用 Spark JDBC 之前需要加入 jdbc 的依赖，使用 ```load()``` 方法或者 ```jdbc()```方法将数据转换为 DataFrame：
```scala
// 使用 SparkSession#load 方法加载
val df = spark.read
  .format("jdbc")
  .option("url","jdbc:mysql://10.60.49.69:3306")
  .option("dbtable", "label.label")
  .option("driver", "com.mysql.jdbc.Driver")
  .option("user", "root")
  .option("password", "Recommend33;")
  .load()
  .show

// 使用 SparkSession#jdbc 方法加载
val properties = new Properties()
properties.setProperty("user", "root")
properties.setProperty("password", "Recommend33;")
properties.setProperty("driver", "com.mysql.jdbc.Driver")
val df = spark.read.jdbc("jdbc:mysql://10.60.49.69:3306", "label.label", properties).show
```
使用 ```save()``` 或者 ```jdbc()``` 方法将数据存储到 mysql：
```scala
jdbcDF.write
  .format("jdbc")
  .option("url", "jdbc:postgresql:dbserver")
  .option("dbtable", "schema.tablename")
  .option("user", "username")
  .option("password", "password")
  .save()
  
jdbcDF.write
  .option("createTableColumnTypes", "name CHAR(64), comments VARCHAR(1024)")
  .jdbc("jdbc:postgresql:dbserver", "schema.tablename", connectionProperties)
```
### 性能调优
## Spark Streaming
Spark Stream 在接收到实时输入数据流之后将数据划分为批次然后传给 Spark Engine 处理，按批次生成最后的结果流
### Dstream
Spark Streaming 提供一种称为 DStream 的高级抽象连续数据流，DStream 可以从数据源的输入数据流创建，也可以在其他 DStream 上应用高级操作创建。

DStream 的核心思想是将计算作为一系列较小时间间隔的、无状态的、确定批次的任务，每个时间间隔内接收到的输入数据被可靠的存储在集群中作为输入数据集应用于 Spark 的算子。
#### 初始化 StreamingContext
StreamingContext 是 Spark Streaming 程序的入口点，Spark Streming 编程首先需要初始化 StreamingContext：
```scala
val sc = SparkSession.builder().appName("HDFSWordCount").master("local[2]").getOrCreate().sparkContext
val ssc = new StreamingContext(sc, Seconds(1))
```
#### Input Dstream
Input DStream 是从流式数据源中获取的原始数据流，Spark Streaming 有两种类型的流式输入数据源：
- 基本输入源：能够直接应用于 StreamingContext API 的输入源，如文件系统、套接字连接
- 高级输入源：能够应用于特定工具类的输入源，如 Kafka、Flume 等，需要引入额外的依赖

每个 Input DStream (文件流除外)都会对应一个单一的接收器对象，接收器对象从数据源接收数据并存入 Spark 内存中进行处理，每个 Input DStream 对应单一的数据流，可以创建多个 Input DStream 并行接受多个数据流。

每个接收器是一个长期运行在 Worker 或者 Executor 上的任务，因此将占用分配给 Spark Streming 应用程序的一个核，为了保证一个或多个接收器能够接受数据，需要分配给 Spark Streaming 应用程序足够多的核：
- 当分配个 Spark Streaming 应用程序的核数小于等于接收器的数量时，系统可以接收数据但是不能处理
- 运行本地模式时只会有一个核来运行任务，此时接收器独占这个核，因此程序不能处理数据(文件流除外，因为文件流不需要接收器)
##### 基本输入源
- 文件流：用于兼容 HDFS API 的文件系统中读取文件中的数据
  ```scala
  streamingContext.fileStream[KeyClass, ValueClass, InputFormatClass](dataDirectory)
  ```
  对于文本文件来说，可以直接使用：
  ```scala
  streamingContext.textFileStream(dataDirectory)
  ```
  Spark Streaming 将监控 dataDirectory 目录并处理该目录中创建的任何文件(不支持嵌套目录中的文件)：
  - 文件必须具有相同的数据格式
  - 通过移动或者重命名的方式创建文件
  - 文件一旦创建就不能修改内容
- Socket 流：通过监听 Socket 端口接收的数据
  ```scala
  // converter 是将字节流转换为对象的函数
  def socketStream[T: ClassTag](
    hostname: String,
    port: Int,
    converter: (InputStream) => Iterator[T],
    storageLevel: StorageLevel
  ): ReceiverInputDStream[T]
  ```
  如果是文本则不需要转换函数：
  ```scala
  def socketTextStream(
      hostname: String,
      port: Int,
      storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK_SER_2
    ): ReceiverInputDStream[String]
  ```
- RDD 队列流：使用 streamingContext.queueStream(queueOfRDD) 创建基于 RDD 队列的 DStream，用于调试
  ```scala
  def main(args: Array[String]): Unit = {
    val sc = SparkSession.builder().appName("Streaming").master("local[*]").getOrCreate().sparkContext

    val ssc = new StreamingContext(sc, Seconds(1))
    val rddQueue = new mutable.Queue[RDD[Int]]()
    val inputStream = ssc.queueStream(rddQueue, true, null)
    inputStream.map(x =>(x % 10, 1)).reduceByKey(_ + _).print(10)
    ssc.start()

    for(_ <- 1 to 30){
      rddQueue += ssc.sparkContext.makeRDD(1 to 1000, 10)
      Thread.sleep(1000)
    }

    ssc.awaitTerminationOrTimeout(1000)
    ssc.stop()
  }
  ```
- 自定义数据源：Input DStream 可以创建自定义数据源，需要自定义数据接收器然后将接收到的数据推给 Spark：
  ```scala
  ```
##### 高级输入源
高级输入源依赖于其他输入源实现的依赖，因此需要引入依赖：
```scala
val sc = SparkSession.builder().appName("Streaming").master("local[*]").getOrCreate().sparkContext
val ssc = new StreamingContext(sc, Seconds(1))
val kafkaParam = Map[String, Object](
  "bootstraps.server" -> "localhost9092",
  "key.deserializer" -> classOf[StringDeserializer],
  "value.deserializer" -> classOf[StringDeserializer],
  "group.id" -> "group",
  "auto.offset.reset" -> "latest",
  "enable.auto.commit" -> (false: java.lang.Boolean)
)
val stream = KafkaUtils.createDirectStream(ssc, LocationStrategies.PreferBrokers, ConsumerStrategies.Subscribe[String, String](Array("topic-test"), kafkaParam))
stream.foreachRDD(f =>{
  if(f.count() > 0) f.foreach(f => println(f.value()))
})
ssc.start()
ssc.awaitTerminationOrTimeout(1000)
```
#### DStream 转换操作
和 RDD 类似，DStream 转换操作是在一个或多个 DStream 上创建新的 DStream。DStream 提供了多种转换：
- def map[U: ClassTag](mapFunc: T => U): DStream[U] 返回新的 DStream，由每个元素经过 mapFunc 函数转换后组成
- def flatMap[U: ClassTag](flatMapFunc: T => TraversableOnce[U]): DStream[U]
- def filter(filterFunc: T => Boolean): DStream[T]
- def filter(filterFunc: T => Boolean): DStream[T]
- def union(that: DStream[T]): DStream[T]
- def count(): DStream[Long]
- def reduce(reduceFunc: (T, T) => T): DStream[T]
- def countByValue(numPartitions: Int = ssc.sc.defaultParallelism)(implicit ord: Ordering[T] = null): DStream[(T, Long)]
- def transform[U: ClassTag](transformFunc: RDD[T] => RDD[U]): DStream[U]
#### DStream 状态操作
状态操作是数据的多批次操作之一，包括所有基于 Window 的操作和 updateStateByKey 操作：
##### window 操作
Spark Streaming 提供了基于 Window 的计算，允许通过滑动窗口对数据进行转换，当窗口在 DStream 按定义的时间间隔滑动时，落入窗口内的 RDD 被视为一个个窗口化的 DStream，因此任何窗口都需要指定两个重要参数：
- 窗口长度(window length)：表示窗口的持续时间，必须是 DStream 批次间隔(创建 StreamingContext 时指定)的倍数
- 滑动窗口的时间间隔(slide interval)：表示执行基于窗口操作计算的时间间隔，必须是 DStream 批次间隔(创建 StreamingContext 时指定)的倍数

Spark Streaming 提供了多个基于窗口的操作：
- def window(windowDuration: Duration, slideDuration: Duration): DStream[T] 返回基于源 DStream 窗口化的新的 DStream
- def countByWindow(windowDuration: Duration,slideDuration: Duration): DStream[Long] 返回 DStream 中的 RDD 只有一个元素表示在这个窗口中元素的个数

```scala
```
##### updteStateByKey 操作
updateStateByKey 操作应用给定函数的原状态和新值，返回一个新状态的 DStream。使用此功能有两个步骤：
- 定义状态，这个状态可以是任意的数据类型
- 定义状态更新函数，应用给定函数从原状态和新值更新新状态
```scala
```
#### DStream 输出操作
输出操作将 DStream 的数据输出到外部系统，执行输出操作后 DStream 的变换才会开始执行。

使用 ```saveAsObjectFiles``` 可以将 DStream 中的内容保存为文本文件，如果使用 ```saveAsHadoopFiles``` 则保存到 HDFS 上；

使用 ```foreachRDD``` 可以在 DStream 中的每个 RDD 执行函数保存到外部系统，具体使用时在 RDD 上使用 ```foreachPartition``` 在每个分区进行操作
#### DataFrame 和 SQL
#### 缓存和持久化
DStream 可以将数据持久化在内存中，通过调用 ```persist``` 函数可以自动把 DStream 中的每一个 RDD 存储在内存中，Dstream 的持久化策略是将数据序列化在内存中，因此序列化和反序列化需要消耗额外的 CPU 性能。

基于窗口或状态的操作无需显式的调用 persist 方法持久化，DStream 会自动持久化到内存中；通过网络接收的数据源默认采取保存两份序列化后的数据在两个不同的节点上的持久化策略从而实现容错
#### 检查点
Spark Streaming 的检查点具有容错机制，支持两种数据类型的检查点：元数据检查点和数据检查点。
- 元数据检查点在类似 HDFS 的容错存储上保存 Streaming 计算信息，这种检查点用来恢复运行 Streaming 应用程序失败的 Driver 进程
- 数据检查点通过周期性检查将转换 RDD 的中间状态进行可靠存储进而切断无限增加的依赖链，在 updateStateByKey 或者 reduceByKeyAndWindow 时需要提供检查点路径用于对 RDD 进行周期性的检查，主要用于恢复有状态的转换操作

为了让 Spark Streaming 应用能够被恢复，需要启动检查点即设置一个容错的、可靠的文件系统路径保存检查点信息，同时设置时间间隔：
```scala
ssc.checkpoint(checkpointDirectory)
dstream.checkpoint(checkpointInterval)
```
Spark Streaming 需要保存中间数据到存储系统可能会导致相应的批处理时间变长，因此需要设计检查点的时间间隔。对于有状态转换操作，检查点默认间隔设置为 DStream 的滑动间隔倍数，至少是 10s。
#### 性能调优
Spark Streaming 应用程序需要考虑两方面：减少每批次处理时间、合理设置窗口大小使得数据处理和数据接收同步
##### 优化运行时间
运行时间优化主要包括：提升数据接收和处理的并行度、减少序列化和反序列化、减少任务提交和分发开销。
- 提升数据接收的并行度：主要通过提升 Receiver 的并发度和调整 Receiver 的 RDD 数据分区时间隔
  - 提升 Receiver 的并发度：在 Worker 节点上对每个输入 DStream 创建一个 Receiver 用以接收不同分区的数据流从而实现并行接收数据提高系统吞吐量。多 DStream 可以通过联合在一起创建一个 DStream：
    ```scala
	streamingContext.union(kafkaStreams)
	```
  - 调整 Receiver 的 RDD 数据分区时间隔：通过修改 ```spark.streaming.blockInterval``` 这个参数，每批次的任务数量大致为 (batchInterval/blockInterval) 个，任务数量太少会导致有些资源空闲
- 提升数据处理的并行度  
并行任务的默认数量由 ```spark.default.parallelism``` 配置属性决定，合理的配置数据处理的并行度确保均衡的使用集群的资源
##### 合理设置批次大小
### Structured Stream

## MLlib
## GraphX

## Spark 基本架构
### Cluster Manager
Spark 的集群管理器，主要负责对整个集群资源的分配与管理。Cluster Manager 在 Yarn 部署模式下为 Resource Manager；在 Mesos 部署模式下为 Mesos Master；Cluster Manager 分配的资源属于一级分配，它将各个 Worker 上的内存、CPU 等资源分配给 Application，但是并不负责对 Executor 的资源分配。
### Worker
Spark 的工作节点，在 Yarn 模式下实际由 NodeManager 替代。Worker 节点主要负责：
- 将自己的内存、CPU 等资源通过注册机制告知 Cluster Manager
- 创建 Executor
- 将资源和任务进一步分配给 Executor
- 同步资源信息、Executor 状态信息给 Cluster Manager

Standalone 模式下，Master 将 Workder 上的内存、CPU 以及 Executor 等资源分配给 Application 后将命令 Worker 启动 CoarseGrainedExecutorBackend 进程，此进程会启动 Executor。
### Executor
执行任务的组件，主要负责任务的执行以及与 Worker 和 Driver 的信息同步
### Driver
Application 的驱动程序，Application 通过 Driver 与 Cluster Manager 和 Executor 通信。Driver 可以运行在 Application 中，也可以有 Application 提交给 Cluster Manager 并由其安排 Worker 运行。
### Application
用户使用 Spark 提供的 API 编写的应用程序，Application 通过 Spark API 将进行 RDD 的转换和 DAG 的构建并通过 Driver 将 Application 注册到 Cluster Manager。

### Spark 编程模型
- 使用 SparkContext 提供的 API 编写 Driver 应用程序
- 使用 SparkContext 提交的用户程序首先会通过 RpcEnv 向 Cluster Manager 注册 Appliction 并且告知集群管理器需要的资源数量，集群管理器根据应用需要的资源给 Application 分配对应的 Executor 并且在 Worker 上启动 CoarseGrainedSchedulerBackend 进程创建 Exectuor。CoarseGrainedSchedulerBackend 在启动的过程中将通过 RpcEnv 直接向 Driver 注册 Executor 的资源信息，TaskScheduler 将保存已经分配给应用的 Executor 资源的地址、大小等相关信息。RDD 被 DAGScheduler 划分 Stage 并创建创建一组 Task 之后就提交给 TaskScheduler，TaskScheduler 对批量的 Task 按照 FIFO 或者 FAIR 调度算法进行调度，然后给 Task 分配 Executor 资源并将 Task 发送给 Executor 执行。

