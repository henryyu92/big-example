
### DataStream API
DataStream 是 Flink 流处理的数据集，转换和计算操作都是在 DataStream 上进行。DataStream API 分为三部分：
- DataSource 定义数据接入功能，负责将外部数据接入 Flink 系统并将数据转换为 DataStream 数据集
- Transformation 定义了对 DataStream 数据集的各种转换和计算
- DataSink 负责将计算后的数据集写出到外部系统中，如文件系统或 Kafka
### DataSource
DataSource 模块定义了 DataStream API 中的数据输入操作，Flink 将数据源分为内置数据源和第三方数据源两种类型。内置数据源包含文件系统、Socket 网络端口以及集合数据类型，Flink 内部已经实现可以直接调用；第三方数据源定义了 Flink 和外部系统数据交互的逻辑，Flink 提供了丰富的第三方数据源连接器(Connector)，也可以自定义类实现 SourceFunction 接口并封装成第三方数据源的 Connector 完成与第三方数据源的交互。
#### 文件数据源
Flink 支持读取文件内容并将其转换为分布式数据集 DataStream 进行处理。可以通过```StreamExecutionEnvironment``` 的 readTextFile 方法直接读物文本文件，也可以使用 readFile 方法通过指定的 InputFormat 来读取特定数据类型的文件，也可以自定义类实现 InputFormat 来实现读取自定义数据类型的文件；
```scala
// 读取文本文件
env.readTextFile("/text/file/path")
// 读取 InputFormat 形式的文件
env.readFile(new CsvInputFormat[String](new Path("/csv/file/path")) {
      override def fillRecord(reuse: String, parsedValues: Array[AnyRef]): String = return null
    }, "/csv/file/path", FileProcessingMode.PROCESS_CONTINUOUSLY, 10)
```
除了 InputFormat 外，readFile 方法可以指定：文件处理模式(FileProcessingMode)、文件路基过滤过滤器(FilePathFilter)、检测文件变化时间间隔(interval) 等，其中文件处理模式有两种分别为 ```FileProcessingMode#PROCESS_ONCE``` 表示只处理一次就退出，```FileProcessingMode#PROCESS_CONTINUOUSLY``` 周期性的处理变化的数据。
#### Socket 数据源
Flink 支持从 Socket 端口接入数据，调用 ```StreamExecutionEnvironment``` 的 socketTextStream 方法指定数据源的 IP、端口、数据分隔符和最大重试次数。数据分隔符负责将数据切割成 Record 数据格式，最大重试次数指定在端口异常的情况下进行重连的次数：
```scala
env.socketDataStream("localhost", 8888, "\n", 10)
```
#### 集合数据源
Flink 可以直接 Java 或 Scala 的集合转换成 DataStream 数据集，本质上是将本地集合中的数据分发到远程并行执行的节点中。Flink 支持从 Collection 和 Iterator 序列转换成 DataStream 数据集：
```scala
// 通过 fromElements 从元素集合中创建 DataStream 数据集
val dataStream = env.fromElements(Tuple2(1, 2), Tuple2(1, 5), Tuple2(1, 7), Tuple2(1, 4), Tuple2(1, 2))
// 通过 fromCollection 从数组转换创建 DataStream 数据集
val dataStream = env.fromCollection(Array("hello", "world"))

val dataStream = env.fromParallelCollection(new NumberSequenceIterator(1, 10))
```
#### 数据源连接器
Flink 通过实现 SourceFunction 定义了非常丰富的第三方数据连接器，在使用是需要引入这些连接器的依赖，使用 addSource 方法加入第三方数据连接器：
```scala
val properties = new Properties()
properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
properties.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, "test")
properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "test-group")

val env = StreamExecutionEnvironment.getExecutionEnvironment

val source = env.addSource(new FlinkKafkaConsumer[String](
  "test-topic",
  new SimpleStringSchema(Charset.forName("utf-8")),
  properties)
)
```
通过自定义 DeserializationSchema 可以实现将接入数据转换成自定义数据结构。
#### 自定义数据源连接器
可以通过实现 SourceFunction 自定义单线程接入的数据连接器，也可以通过实现 ParallelSourceFunction 接口或者继承 RichParallelSourceFunction 定义并发数据源连接器；DataSource 定义完成之后使用 addSource 方法添加自定义的数据连接源，这样就将外部数据源中的数据转换为 DataStream[T] 数据集合，其中 T 表示 SourceFunction 返回值的类型。
```scala
class SelfDefineConnection extends SourceFunction[String]{
  
var count = new AtomicInteger(0)
@volatile var break = false;

  override def run(ctx: SourceFunction.SourceContext[String]): Unit = {
    while (!break){
      TimeUnit.SECONDS.sleep(1)
      ctx.collectWithTimestamp(String.valueOf(count.getAndIncrement()), System.currentTimeMillis())
    }
  }

  override def cancel(): Unit = {
  break = true
  }

}


object SelfDefineConnection{
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.addSource(new SelfDefineConnection).print()
    env.execute("SelfSourceConnector")
  }
}
```
### Transformation
通过从一个或多个 DataStream 生成新的 DataStream 的过程称为 Transformation 操作。在转换过程中每种操作类型被定义为不同的 Operator，Flink 程序能够将多个 Transformation 组成一个 DataFlow 的拓扑。DataStream 的转换操作可分为：
- Single-DataStream：定义了对单个 DataStream 数据集元素的处理逻辑
- Multi-DataStream：定义了对多个 DataStream 数据集元素的处理逻辑
- 物理分区：定义了对数据集中的并行度和数据分区调整转换的处理逻辑

#### Single-DataStream 操作
- ```Map [DataStream -> DataStream]```：调用用户定义的 MapFunction 对 DataStream[T] 数据进行处理，形成新的 DataStream[T]，其中数据格式可能会发生变化，常用作对数据集内数据的清洗和转换。
  
  ```scala
  val dataStream = env.fromElements(("a", 3), ("d", 4), ("c", 2)).map(t=>(t._1, t._2 + 1))
  ```
- ```FlatMap [DataStream -> DataStream]```：主要处理输入一个元素产生一个或者多个元素，传入的参数为 FlatMapFunction。
  ```scala
  env.fromCollection(Array("hello world", "good good study", "day day up")).map(_.split(" ")).flatMap(x => {
    var list = List[String]()
    x.foreach{v =>
      list = v :: list
    }
    list.toArray[String]
  }).map((_, 1)).keyBy(0).sum(1).print
  ```
- ```Filter [DataStream -> DataStream]```：按照条件对输入数据集进行筛选操作，将符合条件的数据集输出，不符合条件的数据过滤。
  ```scala
  env.fromCollection(Array(1, 3, 4)).filter(_ % 2 == 0)
  ```
- ```KeyBy [DataStream -> KeyedStream]```：根据指定的 key 将输入的 DataStream[T] 数据格式转换为 KeyedStream[T]，也就是在数据集中执行 Partition 操作，将相同 key 值的数据放置在相同的分区中。
  ```scala
  env.fromElements((1, 5), (2, 2),(2,4),(1,3)).keyBy(0)
  ```
- ```Reduce [KeyedStream -> DataStream]```：将输入的 KeyedStream 通过 ReduceFunction 滚动地进行数据聚合处理，ReduceFunction 必须满足运算结合律和交换律。
  ```scala
  env.fromElements((1, 5), (2, 2),(2,4),(1,3)).keyBy(0).reduce((x, y) => (x._1, x._2 + y._2)).print
  ```
- ```Aggregations [KeyedStream -> DataStream]```：根据指定的字段进行聚合，滚动地产生一系列数据聚合的结果，是对 reduce 的封装，包含 sum、min、minBy、max、maxBy 等。
  ```scala
  val stream = env.fromElements((1, 5), (2, 2),(2,4),(1,3)).keyBy(0)
  stream.sum(1).print
  stream.min(1).print
  stream.max(1).print
  stream.minBy(1).print
  stream.maxBy(1).print
  ```
#### Multi-DataStream 操作
- ```Union [DataStream -> DataStream]```：将两个或者多个输入的数据集合并成一个数据集，需要保证数据集的格式一致，输出结果集的格式和输入数据集的格式一致。
  ```scala
  val stream1 = env.fromElements(("a", 3), ("d", 4), ("c", 2), ("c", 5), ("a", 5))
  val stream2 = env.fromElements(("d", 1), ("s", 2), ("a", 4), ("e", 5), ("a", 6))
  val stream3 = env.fromElements(("a", 2), ("d", 1), ("s", 2), ("c", 3), ("b", 1))

  stream1.union(stream2).print
  stream1.union(stream2, stream3).print
  ```
- ```Connect [DataStream -> ConnectedStreams]```：合并两种或多种不同数据类型的数据集，合并后会保留原来数据集的数据类型。DataStream 数据集经过 connect 操作后成为 ConnectedStreams 数据集：
  ```scala
  val d1 = env.fromElements(("a", 3), ("d", 4), ("c", 2), ("c", 5), ("a", 5))
  val d2 = env.fromElements(1, 2, 4, 5, 6)

  val connected :ConnectedStreams[(String, Int), Int] = d1.connect(d2)
  ```
  ConnectedStreams 数据集不能直接输出，提供了 map 和 flatMap 操作转换为 DataStream，需要定义 CoMapFunction 和 CoFlatMapFunction 分别对两个 DataStream 数据集进行处理，返回一个 DataStream 数据集。**CoMapFunction 和 CoFlatMapFunction 中的函数会以多线程的方式执行，执行的顺序不定，因此会产生乱序**
  ```scala
  connected.map(new CoMapFunction[(String, Int), Int, (Int, String)] {
    override def map1(value: (String, Int)): (Int, String) = {
      (value._2, value._1)
    }

    override def map2(value: Int): (Int, String) = {
      (value, "default")
    }
  }).print
    
    
  connected.flatMap(new CoFlatMapFunction[(String, Int), Int, (String, Int, Int)] {

    val number = new AtomicInteger(0)

    override def flatMap1(value: (String, Int), out: Collector[(String, Int, Int)]): Unit = {
      out.collect((value._1, value._2, number.get()))
    }

    override def flatMap2(value: Int, out: Collector[(String, Int, Int)]): Unit = {
      number.getAndAdd(value)
    }
  }).print
  ```
  除了 map 和 flatMap 外，ConnectedStream 还提供了 keyBy 和 broadcast 操作用于将两个数据集通过指定条件相关联，然后产生相关性比较强的结果数据集。keyBy 函数是将相同 key 的数据路由到一个相同的 Operator 中，而 broadcast 会在执行计算逻辑之前将另外一个数据集广播到所有并行计算的 Operator 中。
  ```scala
  connected.keyBy(0, 1)

  d1.connect(d2.broadcast)
  ```
- ```Split [DataStream -> SplitStream]```：将一个 DataStream 数据集按照条件进行拆分，形成两个数据集的过程。每个接入的数据都会被路由到一个或者多个输出数据集中：
  ```scala
  val data = env.fromElements(("a", 3), ("d",4), ("c", 2), ("c", 5), ("a", 5))
  val split = data.split(t=> if(t._2 % 2 == 0) Seq("even") else Seq("odd"))
  ```
- ```Select [SplitStream -> DataStream]```：split 函数只是对输入数据集进行标记，并没有真正对数据集实现切分，需要借助 Select 函数根据标记将数据切分成不同的数据集：
  ```scala
  val even = split.select("even")
  val odd = split.select("odd")
  val all = split.select("even", "odd")
  ```
- ```Iterate [DataStream -> IterativeStream]```：适合于迭代计算场景，需要一个创建两个 DataStream 的变换，第一个 DataStream 作为下次迭代的开始，第二个作为迭代部分的输出到下游数据集。
  ```scala
  env.fromElements(3, 1, 2, 1, 5)
    .iterate((input: ConnectedStreams[Int, String]) =>{
      val head = input.map(i => (i + 1).toString, s => s)
      // 数据为偶数则作为下一次迭代，数据为奇数则输出到下游数据集
      (head.filter(_ == "2"), head.filter(_ != "2"))
  }, 1000)
  ```
#### 物理分区操作
物理分区操作的作用是根据指定的分区策略将数据重新分配到不同节点的 Task 上执行。使用 DataStream 提供的 API 对数据处理过程中依赖算子本身对数据分区的控制，如果希望自己控制数据分区就需要通过定义物理分区策略对数据集进行重新分布处理。Flink 已经提供了常见的分区策略：
- 随机分区(Random Partitioning)：通过随机的方式将数据分配在下游算子的每个分区中，分区相对均衡，但是较容易失去原有数据的分区结构，DataStream 中的 shuffle 实现数据集的随机分区。
  ```scala
  dataStream.shufle
  ```
- 平衡分区(Roundrobin Partitioning)：通过循环的方式对数据集中的数据进行重分区，能够尽可能保证每个分区的数据平衡，当数据集发生数据倾斜时使用这种策略就是比较有效的优化方法，DataStream 中的 rebalance 方法实现了数据集的平衡分区。
  ```scala
  dataStream.rebalance
  ```
- 按比例分区(Rescaling Partitioning)：按比例分区也是一种通过循环的方式进行数据重平衡的分区策略，当使用 Roundrobin Partitioning 时，数据会全局性的通过网络介质传输到其他的节点完成数据的重新平衡，而 Rescaling Partitioning 仅仅会对上下游继承的算子数据进行重平衡，具体的分区主要根据上下游算子的并行度决定，上游一个分区中的数据会按照比例路由到下游的分区中，DataStream 中的 rescale 方式实现数据集的按比例分区。
  ```scala
  dataStream.rescale
  ```
- 广播操作  
  广播策略将输入的数据集复制到下游算子的并行 Task 中，下游算子中的 Task 可以直接从本地内存中获取广播数据集而不需要依赖网络传输；这种分区策略适合小数据集。DataStream 中的 broadcast 方法实现了广播分区
  ```scala
  dataStream.broadcast
  ```
- 自定义分区  
  自定义分区只需要实现 Partitioner 接口并且在使用时调用 DataStream 的 partitionCustom 方法将自定义的分区器应用于数据集。
```scala
object SelfPartitioner extends Partitioner[String]{

    val r = scala.util.Random

    override def partition(key: String, numPartitions: Int): Int = {
      if(key.contains("flink")) 0 else r.nextInt(numPartitions)
    }

    def main(args: Array[String]): Unit = {
      val env = StreamExecutionEnvironment.getExecutionEnvironment
      env.fromElements(("flink", 1), ("spark", 2), ("scala", 0), ("python", 1)).partitionCustom(SelfPartitioner, 0).print

      env.execute("Self Partitioner")
    }
  }
```
### DataSink
数据集经过 Transformation 之后需要输出到外部存储介质或者消息中间件中，Flink 中将 DataStream 数据输出到外部系统的过程称为 DataSink 操作。Flink 内部定义了 Kafka、Cassandra、Kinesis、ElasticSearch、HDFS 等支持，另外 Apache Bahir 框架提供了一些其他的扩展。
#### 基本数据输出
基本数据输出包含了文件输出、客户端输出、Socket 网络端口等，这些输出方法已经在 Flink DataStream 中内置。
```scala
dataStream.writeAsCsv("csv/file/path")

dataStream.writeAsText("text/file/path")

dataStream.writeAsSocket
```
#### 第三方数据输出
Flink 提供了 DataSink 类操作算子处理数据的输出，所有的数据输出操作都可以基于实现 SinkFunction 定义完成。Flink 提供了 addSink() 方法允许将数据集输出到其他第三方的数据输出策略。
```scala
val stream = env.fromElement("Alex", "Peter", "Linda")

```
### 时间概念
流式数据处理最大的特点是数据上具有时间的属性特征，Flink 将时间区分为三种概念：
- 事件时间(Event Time)：Event Time 是每个独立事件发生的时间，这个时间通常在事件进入 Flink 之前就已经嵌入事件中，时间顺序取决于事件产生，具有不变性。由于网络问题，事件时间往往会出现数据乱序、延迟等问题，基于 Event Time 的概念数据处理可以借助于数据本身的时间还原事件的先后关系。
- 接入时间(Ingestion Time)：Ingestion Time 是事件接入 Flink 系统的时间，依赖于 SourceOperator 的系统时钟。Ingestion Time 在数据接入的过程中一旦生成就不会改变和后续的数据处理时间没有关系，从而不会因为某台机器时间不同步或者网络延时导致计算结果不准确的问题，Ingestion Time 不能处理乱序事件。
- 处理时间(Processing Time)：Processing Time 是指数据在操作算子计算过程中获取到的所在机器的时间，Processing Time 比较简单处理起来性能相对较高并且延时较低，但是由于数据乱序和时间不一致问题会影响计算结果，因此只适用于时间计算精度要求不高的场景
#### 时间概念指定
Flink 中默认的是 Processing Time，如果需要指定其他时间概念，需要调用 ```StreamExecutionEnvironment#setStreamTimeCharacteristic()``` 方法指定全局时间概念。
```scala
val env = StreamExecutionEnvironment.getExecutionEnvironment()
env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime)
```
### Watermark
通常情况下，由于网络或系统等外部因素影响，事件数据往往不能及时传输到 Flink 系统中导致数据乱序到达或者延迟到达等问题，因此需要保证数据全部到达或者延迟到达 Flink 时能够像预期一样正确的计算出正确且连续的结果。

Flink 使用 Watermark 机制来处理数据延迟，它能够衡量数据处理进度。Flink 用读取进入系统的最新事件时间减去固定的时间间隔作为 Watermark，该时间间隔为理论上最大的延时，也就是说不会有在 Watermark 时间之前的数据还未到达。

当事件接入 Flink 系统时，Source Operator 会根据最新的 Event Time 产生 Watermark ，Watermark 保证所有 Event Time 小于 Watermark 的事件都已经到达，此时如果 Window 的 end-time 小于 Watermark 则触发窗口计算。
#### 顺序事件 Watermark
如果数据元素的事件时间是有序的，watermark 时间戳会随着数据元素的事件时间按顺序生成，此时 Watermark 的变化和事件时间保持一致达到理想状态。当 watermark 时间大于 window 结束时间就会触发 window 的数据计算并创建一个新的 window 来等待新的事件。
#### 乱序事件 Watermark
#### 并行数据流 Watermark
#### Watermark 生成
使用 Event Time 处理流式数据时除了需要指定 TimeCharacteristic 外还需要在程序中指定 Event Time 的时间戳在数据中的字段信息并根据时间戳创建相应的 Watermark。Flink 支持两种方式生成 Watermark，一种方式在 DataStream Source 算子的 SourceFunction 中定义，另外一种方式是通过自定义的 Timestamp Assigner 和 Watermark Generator 生成。
- SourceFunction 中定义  
  在 DataStrem Source 算子中指定也就是说在数据进入到 Flink 中就直接指定分配 EventTime 和 Watermark，需要重写 SourceFunction 接口中 ```run()``` 方法实现数据生成逻辑，同时需要调用 ```SourceContext#collectWithTimestamp()``` 方法生成 EventTime，调用 ```SourceContext#emitWatermark()``` 生成 Watermark。
  ```scala
  val env = StreamExecutionEnvironment.getExecutionEnvironment
  env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    
  val input = List(("a", 1L, 1), ("b", 1L, 1), ("b", 3L, 1))
  val source = env.addSource((ctx: SourceContext[(String, Long, Int)]) =>{
    input.foreach(value =>{
      // 增加 Event Time
      ctx.collectWithTimestamp(value, value._2)
      // 创建 Watermark，设定最大延时为 1
      ctx.emitWatermark(new Watermark(value._2 - 1))
    })
    ctx.emitWatermark(new Watermark(Long.MaxValue))
  })

  source.print
  env.execute("Stream Watermark")
  ```
- TimestampAssigner 指定  
  如果使用了已经指定的外部数据源连接器就不能再实现 SourceFunction 接口来生成流式数据以及相应的 Event Time 和 Watermark，这种情况下需要借助 Timestamp Assigner 来管理数据流中的 Timestamp 元素和 Watermark。Timestamp Assigner 一般是跟在 Data Source 算子后面指定只要保证在第一个时间相关的 Operator 之前即可，Timestamp Assigner 会覆盖 SouceFunction 中自定义的 Timestamp 和 Watermark。

  Flink 支持 Periodic Watermark(根据设定时间间隔周期性地生成 Watermark) 和 Punctuated Watermark(根据接入的数据量生成)，分别通过 ```AssignerWithPeriodicWatermarks``` 和 ```AssignerWithPunctuatedWatermarks``` 接口定义 。

  Flink 实现了两种 PeriodicWatermarkAssigner，一种为升序模式，会将数据中的 Timestamp 根据指定字段提取并用当前的 Timestamp 作为最新的 Watermark，这种 TimestampAssigner 比较适合于事件按顺序生成，没有乱序事件的情况；另一种是通过设定固定的时间间隔来指定 Watermark 落后于 Timestamp 的区间长度，也就是最长容忍迟到多长时间内的数据到达系统。
  - 使用 AscendingTimestampAssigner 指定 timestamp 和 watermark  
  通过 ```assignAscendingTimestamp()``` 方法指定 Timestamp 字段不需要显式的指定 watermark，因为已经在系统中默认使用 Timestamp 创建 watermarker：
  ```scala
  env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
  val in = env.fromCollection(List(("a", 1L, 1), ("b", 1L, 1), ("b", 3L, 1),("a", 2L, 2)))
  in.assignAscendingTimestamps(t => t._3).keyBy(0).timeWindow(Time.seconds(10)).sum(2).print
  env.execute("Timed Window")
  ```
  - 使用固定时延间隔的 TimestampAssigner 指定 Timestamp 和 Watermark。通过创建 BoundedOutOfOrdernessTimestampExtractor 实现类来定义 TimestampAssigner，第一个参数表示最长时延，第二参数表示抽取逻辑：
  ```scala
  in.assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[(String, Long, Int)](Time.seconds(10)) {
      override def extractTimestamp(element: (String, Long, Int)): Long = {
        element._2
      }
    })
  ```
- 通过实现 AssignerWithPeriodicWatermarks 和 AssignerWithPuncuatedWatermark两个接口分别生成自定义的 PeriodicWatermarks 和 PunctuatedWatermarks。 
  - PeriodicWatermarks 根据固定的时间间隔，周期性的在 Flink 系统中分配 Timestamps 和生成 Watermarks，在定义和实现 AssignerWithPeriodicWatermarks 接口之前需要先在 ExecutionConfig 中调用 setAutoWatermarkInterval 方法设置 Watermarks 产生的时间间隔。
  ```scala
  class PeriodicAssigner extends AssignerWithPeriodicWatermarks[(String, Long, Int)]{
    val maxOutOfOrder = 1000L
    var currentMaxTimestamp : Long = _
    // 定义生成 watermark 逻辑
    override def getCurrentWatermark: Watermark = {
      new Watermark(currentMaxTimestamp - maxOutOfOrder)
    }
    // 定义抽取 timestamp 逻辑
    override def extractTimestamp(element: (String, Long, Int), previousElementTimestamp: Long): Long = {
      val currentTimestamp = element._2
      currentMaxTimestamp = Math.max(currentTimestamp, currentMaxTimestamp)
      currentTimestamp
    }
  }
  ```
  - PunctuatedWatermark 根据一些特殊条件触发生成 Watermark，需要实现 AssignerWithPunctuatedWatermark 接口并复写方法完成抽取 Event Time 和 Watermark 定义逻辑：
  ```scala
  class PunctuatedAssigner extends AssignerWithPunctuatedWatermarks[(String, Long, Int)] {
    // 定义 Watermark 生成逻辑
    override def checkAndGetNextWatermark(lastElement: (String, Long, Int), extractedTimestamp: Long): Watermark = {
      if (lastElement._3 == 0) new Watermark(extractedTimestamp) else null
    }

    // 定义 timestamp 抽取逻辑
    override def extractTimestamp(element: (String, Long, Int), previousElementTimestamp: Long): Long = {
      element._2
    }
  }
  ```
### Window
Flink DataStream API 将窗口抽象成独立的 Operator 并且内建了大多数的窗口算子。在每个窗口算子中包含了 Windows Assigner, Windows Trigger, Evictor, Lateness, Output Tag 以及 Windows Function 等。
- Windows Assigner 指定窗口的类型，定义如何将数据流分配到一个或多个窗口
- Windows Trigger 指定窗口触发的时机，定义窗口满足什么样的条件就触发计算
- Evictor 用于数据剔除
- Lateness 标记是否处理迟到数据，当迟到数据到达窗口中是否触发计算
- Output Tag 标记输出标签，然后再通过 getSideOutput 将窗口中的数据根据标签输出
- Windows Function 定义窗口上数据处理的逻辑
#### Windows Assigner
##### Keyed 和 Non-Keyed 窗口
在运用窗口计算时，Flink 根据上游数据是否为 KeyedStream 类型对应的 Windows Assigner 也会有所不同。上游数据如果是 KeyedStream 类型则调用 DataStream API 的 window 方法指定 Windows Assigner，数据会根据 key 在不同的 Task 实例中并行分别计算然后得出针对每个 key 的结果；如果不是 KeyedStream 类型则调用 windowsAll 方法来指定 Windows Assigner，所有的数据都会在窗口算子中路由到一个 Task 中计算并得到全局结果。
##### Windows Assigner
Flink 支持两种类型的窗口：
- 基于时间的窗口，窗口基于起始时间戳和终止时间戳来决定窗口的大小，数据根据时间戳被分配到不同的窗口中完成计算。Flink 使用 TimeWindow 类来获取窗口的起始时间和终止时间以及该窗口允许进入的最新时间戳信息等元数据
- 基于数量的窗口，根据固定的数量定义的窗口，窗口中接入的数据依赖于数据接入到算子中的顺序，如果数据出现乱序情况，将导致窗口的计算结果不确定。Flink 使用 countWindows 方法来定义基于数量的窗口

Flink 流式计算中通过 Windows Assigner 将接入数据分配到不同的窗口，根据 Windows Assigner 数据分配方式的不同将 Windows 分为 4 大类：滚动窗口(TumblingWindows)、滑动窗口(SlidingWindows)、会话窗口(SessionWindows)、全局窗口(GlobalWindows)，调用 DataStream API 的 windows 或 windowsAll 方法指定 Windows Assigner
###### 滚动窗口
滚动窗口是根据固定时间或大小进行切分，且窗口和窗口之间的元素互不重叠。DataStream API 提供了基于 Event Time 和 Process Time 两种时间类型的滚动窗口，对应的 Assigner 分别为 TumblingEventTimeWindows 和 TumblingProcessTimeWindows。调用 DataStream API 的 window 方法指定 Assigner 并且调用 Assigner 的 of 方法设置窗口的大小。
```scala

```
###### 滑动窗口
滑动窗口在滚动窗口基础之上增加了窗口滑动时间且允许窗口数据发生重叠。

DataStream API 针对滑动窗口提供了基于 Event Time 的 SlidingEventTimeWindow 和基于 Process Time 的 SlidingProcessingTimeWindow。
###### 会话窗口
会话窗口是将某段时间内活跃度较高的数据聚合成一个窗口进行计算，窗口触发的条件是 Session Gap，即在规定的时间内如果没有数据接入则认为窗口结束触发窗口计算。如果数据一直不断的进入则会导致窗口计算始终不会被触发的情况。

会话窗口类型适合非连续性数据处理货周期性产生数据的场景，DataStream API 提供了基于 Event Time 的 EventTimeSessionWindows 和 基于 Process Time 的 ProcessTimeSessionWindows，通过 withGap 方法指定 session Gap。
```scala

```
除了使用 withGap 方法指定固定的 Session Gap 外，Flink 支持动态的调整 Session Gap，只需要实现 SessionWindowTimeGapExtractor 接口并复写 extract 方法完成动态 session Gap 的抽取，然后使用 withDynamic 方法即可：
```scala

```
会话窗口本质上没有固定的起止时间点，底层是为每个进入的数据都创建了一个窗口，最后将距离 Session gap 最近的窗口进行合并然后计算窗口结果，因此需要能够合并的 Trigger 和 Windows Function
###### 全局窗口
全局窗口将所有相同的 key 的数据分配到单个窗口中计算，窗口没有起始和结束时间，需要借助 Trigger 来触发计算并且需要指定相应的数据清理机制，否则数据将会一直留在内存中。
#### Window Function
对数据集定义了 WindowAssigner 之后可以定义窗口内数据的计算逻辑，即 Window Function。Flink 提供了四种类型的 Window Function：ReduceFunction, AggregateFunction, FoldFunction 和 ProcessWindowFunction。

四种类型中 ReduceFunction, AggregateFunction, FoldFunction 是增量聚合函数，ProcessWindowFunction 是全量窗口函数。增量聚合函数计算性能高，占用内存少，主要是因为基于中间状态的计算结果，窗口中只维护中间结果状态值，不需要缓存原始数据；全量窗口函数使用的代价相对较高，性能比较弱，主要因为此时算子需要对所有属于该窗口的接入数据进行缓存，然后等到窗口触发的时候对所有原始数据进行汇总计算。
##### ReduceFunction
ReduceFunction 定义了对输入的两个相同类型的数据元素按照指定的计算方法进行聚合的逻辑，然后输出类型相同的一个结果元素。
```scala
val inputStream = ...
inputStream.keyBy(_._0)
  .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(10)))
  .reduce{(v1, v2)=>(v1._1, v1._2 + v2._2)}
```
可以实现 ReduceFunction 来自定义聚合逻辑：
```scala
inputStream.keyBy(_._1)
  .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(10)))
  .reduce(new ReduceFunction[(Int, Long)]{
    override def reduce(t1:(Int, Long), t2:(Int, Long)):(Int, Long)={
      (t1._1, t1._2 + t2._2)
    }
  })
```
##### AggregateFunction
AggregateFunction 也是基于中间状态计算结果的增量计算函数。AggregateFunction 接口中定义了三个需要复写的方法：add 定义数据的添加逻辑，getResult 定义根据 accumulator 计算结果的逻辑，merge 定义合并 accumulaotr 的逻辑
```scala
class CustomAverageAggregateFunction extends AggregateFunction[(String, Long),(Long, Long), Double]{
  // 初始化 (sum, count)
  override def createAccumulator(): (Long, Long) = (0L, 0L)
  // 定义 (sum, count) 的计算逻辑
  override def add(value: (String, Long), accumulator: (Long, Long)): (Long, Long) = {
    (accumulator._1 + value._2, accumulator._2 + 1)
  }
  // 最终结果
  override def getResult(accumulator: (Long, Long)): Double = accumulator._1 / accumulator._2
  // 合并逻辑
  override def merge(a: (Long, Long), b: (Long, Long)): (Long, Long) = (a._1 + b._1, a._2 + b._2)
}
```
##### ProcessWindowFunction
ProcessWindowFunction 能够支持基于窗口全部数据元素的计算结果
```scala
class CustomStaticProcessFunction extends ProcessWindowFunction[(String, Long, Int), (String, Long, Long, Long,Long, Long), String, TimeWindow]{
  override def process(key: String, context: Context, elements: Iterable[(String, Long, Int)], out: Collector[(String, Long, Long, Long, Long, Long)]): Unit = {
    val sum = elements.map(_._2).sum
    val min = elements.map(_._2).min
    val max = elements.map(_._2).max
    val avg = sum / elements.size
    val windowEnd = context.window.getEnd
    out.collect((key, min, max, sum, avg, windowEnd))
  }
}
```
##### Incremental Aggregation Function 和 ProcessWindowFunction 整合
将 Incremental Aggregation Function 和 ProcessWindowFunction 整合可以充分利用两种函数各自的优势。Flink DataStream API 提供了对应方法：
```scala
val result = inputStream.keyBy(_._1)
  .timeWindow(Time.seconds(10))
  .reduce(
    (r1:(String, Long, Int), r2:(String, Long, Int)) => if (r1._2 > r2._2) r2 else r1,
    (key:String, window:TimeWindow, minReadings:Interable[(String, Long, Int)], out:Collector[(Long, (String, Long, Int))]) =>{
      val min = minReadings.iterator.next()
      out.collect(window.getEnd, min)
    }
  )
```
reduce 方法中定义了两个 Function 分别是 ReduceFunction 和 ProcessWindowFunction，ReduceFunction 定义了数据元素根据指定 key 求取第二个字段对应最小值得逻辑，ProcessWindowFunction 定义了从窗口元数据中获取窗口结束时间属性然后将 ReduceFunction 统计的数据元素的最小值和窗口结束时间共同返回。
##### ProcessWindowFunction 状态操作
除了能够通过 RichFunction 操作 keyedState 之外，ProcessWindowFunction 也可以操作基于窗口之上的状态数据，这类状态称为 Pre-window State。状态数据针对指定的 key 在窗口上存储，每个窗口实例中都会保存每个 key 的状态数据，可以通过 ProcessWindowFunction 中的 Context 对象获取并操作 Pre-window State 数据，Pre-window State 在 ProcessWindowFunction 中有两种：
- globalState：窗口中的 keyed state 数据不限定在某个窗口中
- windowState：窗口中的 keyed state 数据限定在固定窗口中

获取状态数据适用于在同一窗口多次触发计算的场景，或针对迟到的数据来触发窗口计算。使用 Pre-window State 数据需要即使清理状态数据，可以调用 ProcessWindowFunction 的 clear 方法完成对状态数据的清理。
#### Windows Trigger
数据接入窗口后需要满足触发条件才会触发 WindowFunction 计算，每种类型的窗口都有对应的窗口触发机制，保障每一次接入窗口的数据能够按照规定的触发逻辑进行统计计算。Flink 内部定义了多个窗口触发器来控制窗口的触发：
- EventTimeTrigger：通过对比 Watermark 和窗口的 EndTime 确定是否触发窗口，如果 Watermark 的时间大于 Windows EndTime 则触发计算，否则窗口继续等待
- ProcessTimeTrigger：通过对比 ProcessTime 和窗口 EndTime 确定是否触发窗口，如果ProcessTime 大于窗口 EndTime 则触发计算，否则窗口继续等待
- ContinuousEventTimeTrigger：根据间隔时间周期性触发窗口或者 Window 的结束时间小于当前 EventTime 触发窗口计算
- ContinuousProcessingTimeTrigger：根据间隔时间周期性的触发窗口或者 Window 的结束时间小于当前 ProcessTime 触发窗口计算
- CountTrigger：根据接入数据量是否超过设定的阈值确定是否触发窗口计算
- DeltaTrigger：根据接入数据计算出来的 Delta 指标是否超过设定的阈值判断是否触发窗口计算
- PurgingTrigger：可以将任意触发器作为参数转换为 Purge 类型触发器，计算完成后数据将被清理

Flink 支持通过实现 Trigger 接口自定义触发器，然后再 DataStream API 中调用 trigger 方法传入自定义的 Trigger。Trigger 接口定义了多个方法：
- onElement：针对每一个接入窗口的数据元素进行触发操作
- onEventTime：根据接入窗口的 EventTime 进行触发操作
- onProcessingTime：根据接入窗口的 ProcessTime 进行触发操作
- onMerge：对多个窗口进行 Merge 操作，同时进行状态的合并
- clear：执行窗口即状态数据的清除

判断窗口触发方法返回的结果有几种类型：
- CONTINUE：代表当前不触发计算，继续等待
- FIRE：表示触发计算，但是数据继续保留
- PURGE：代表窗口内部数据清除，但不触发计算
- FIRE_AND_PURGE：表示触发计算并清除对应的数据

```scala

```
#### Evictor
Evictor 是 Flink 窗口机制中一个可选的组件，主要作用是对进入 WindowFunction 前后的数据进行剔除处理，通过 DataStream API 的 evictor 方法使用，默认是在WindowsFunction 计算之前对数据进行剔除处理。Flink 内部实现了三种 Evictor：
- CountEvictor：保持在窗口中具有固定数量的记录，将超过指定大小的数据在窗口计算前剔除
- DeltaEvictor：通过定义 DeltaFunction 和指定阈值并计算窗口中的元素与最新元素之间的 delta 大小，如果超过阈值着将当前元素剔除
- TimeEvictor：指定时间间隔将当前窗口中最新元素的时间减去 interval，然后将小于该结果的数据全部剔除，本质是将具有最新时间的数据选择出来，剔除过时的数据
```scala

```
可以通过实现 Evictor 接口自定义数据剔除逻辑：
```scala

```
#### 延迟数据处理
基于 Event-Time 的窗口处理流式数据虽然提供了 Watermark 机制，但只能在一定程度上解决数据乱序问题。在某些情况下数据可能延时非常严重，即使通过 Watermark 机制也无法等到数据全部进入窗口再进行处理，Flink 默认会将这些迟到的数据丢弃，如果需要即使数据延迟也能够正常按照流程处理并输出结果则需要使用 Allowed Lateness 机制来对迟到的数据进行额外的处理。

DataStream API 提供了 allowedLateness 方法指定是否对迟到的数据进行处理，参数表示允许最大的延时时间，Flink 窗口计算过程中会将 window 的 endTime 加上该时间作为窗口最后被释放的结束时间。当接入数据中 EventTime 为超过结束时间但 Watermark 已经超过窗口的 endTime 则直接触发窗口计算，如果 EventTime 超过了结束时间则只能对数据进行丢弃处理。默认情况下 GlobalWindow 的最大 Lateness 时间为 Long.MAX_VALUE 其他窗口类型默认的最大 Lateness 时间为 0。

Flink 提供了 ```sideOutputLateData``` 方法标记迟到数据，然后使用 ```getSildeOutput``` 从窗口中获取 lateOutputTag 标签对应的数据之后转换成独立的 DataStream 数据集进行处理。
```scala

```
#### 窗口计算
对接入的流式数据进行窗口处理的过程是将 DataStream 在窗口中完成计算逻辑，计算完成后会转换成DataStream 以继续进行后续的处理。
##### 独立窗口计算
针对同一个 DataStream 进行不同的窗口处理，窗口之间相对独立，输出结果在不同的 DataStream 中，在运行时会在两个不同的 Task 中执行，相互之间元数据不会进行共享
```scala

```
##### 连续窗口计算
连续窗口计算表示上游窗口的计算结果是下游窗口计算的输入，窗口之间的元数据信息能够共享。
```scala

```
#### 窗口关联
Flink 支持窗口上的多流合并，即在一个窗口中按照相同条件对两个输入数据流进行关联操作，需要保证输入的数据流构建在相同的窗口上并使用相同类型的 key 作为关联条件。
```scala

```
在 Windows Join 中，指定不同的 Windows Assigner，DataStream 的关联过程也相应不同，包括数据计算的方式也会有所不同。
##### 滚动窗口关联
滚动窗口关联操作是将滚动窗口中相同 key 的两个 DataStream 数据集中的元素进行关联并应用用户自定义的 JoinFunction 计算关联结果。
```scala

```
##### 滑动窗口关联
滑动窗口关联操作过程中会出现重叠的关联操作
```scala

```
##### 会话窗口关联
会话窗口关联对两个数据流进行窗口关联操作，窗口中含有两个数据集的元素，并且元素具有相同的 key 则输出关联计算结果
```scala

```
##### 间隔关联
间隔关联的数据元素关联范围不依赖窗口划分，而是通过 DataStream 元素的时间加上或减去指定 interval 作为关联窗口，然后和另外一个 DataStream 的数据元素时间在窗口内进行 Join 操作
```scala

```
### 作业链
Flink 作业中可以指定相应的链条将相关性非常强的转换操作绑定在一起，这样能够让转换过程上下游的 Task 在同一个 Pipeline 中执行，进而避免因为数据在网络或者线程间传输导致的开销。
#### 禁用全局链条
通过禁止全局作业链来关闭整个 Flink 作业的链条，这个操作会影响整个作业的执行情况
```scala
StreamExecutionEnvironment.disableOperatorChaining
```
关闭全局作业链后，创建对应 Operator 的链条需要事先指定操作符，然后再通过使用 startNewChain 方法创建且创建的链条只对当前的操作符和之后的操作符进行绑定，不影响其他的操作。
```scala

```
#### 禁用局部链条
使用 disableChaining 方法禁用当前操作符上的链条
```scala

```
### Flink 状态管理和容错
有状态计算是指在程序计算过程中存储了计算产生的中间结果并提供给后续 Function 或算子使用，状态数据可以维护在堆内存或者对外内存也可以由第三方存储介质存储。无状态计算不会存储计算过程中产生的结果，也不会将结果用于下一步计算过程，程序只会在当前的计算流程中实行计算并输出结果。

无状态计算无法处理复杂的业务场景：
- CEP(复杂事件处理)场景下，获取符合特定规则的事件，状态计算将接入的事件进行存储，然后等待符合规则的事件触发
- 按照时间段进行聚合计算需要利用状态来维护当前计算过程中产生的结果
- 在数据流上实现迭代计算

#### 状态类型
Flink 数据集以能否根据 key 进行分区分为 Keyed State 和 Operator State 两种状态类型
- Keyed State：只能用于 KeyedStream 类型数据集对应的 Function 和 Operator 之上，Keyed State 根据 key 对数据集进行了分区，每个 Keyed State 仅对应一个 Operator 和 key 的组合。Keyed State 可以通过 Key Group 进行管理，主要用于当算子并行度发生变化时自动重新分布 Keyed State 数据。
- Operator State：只和并行的算子实例进行绑定，和数据元素中的 key 无关，每个算子实例中持有所有数据元素中的一部分状态数据。Operator State 支持当算子实例并行度发生变化时自动重新分配状态数据。

Flink 中的 Keyed State 和 Operator State 具有两种形式，一种为托管状态(Managed State)形式，由 Flink Runtime 中控制和管理状态数据并将状态数据转换成内存 Hash Table 或 RocksDB 的对象存储，然后将这些状态数据通过内部的接口持久化到 Checkpoint 中，任务异常时可以通过这些状态数据恢复任务。另外一种是原生状态(Raw State)形式，由算子自己管理数据结构，当触发 Checkpoint 过程中 Flink 并不知道状态数据内部的数据结构，只是将数据转换成 byte 数据存储在 checkpoint 中，当从 Checkpoint 恢复任务时，算子自己再反序列化出状态的数据结构。
##### Managed Keyed State
Flink 中由多种 Managed Keyed State 类型，每种类型都有相应的使用场景：
- ValueState[T]：与 key 对应的单个值的状态
- ListState[T]：与 key 对应元素列表的状态，状态中存放元素的 List 列表
- ReducingState[T]：定义与 key 相关的数据元素单个聚合值得状态，用于存储经过指定 ReduceFunction 计算之后的指标，因此 ReducingState 需要指定 ReduceFunction 完成状态数据的聚合
- AggregatingState[IN,OUT]：定于与 key 对应的数据元素单个聚合值得状态，用于维护数据元素经过指定 AggregateFunction 计算之后的指标
- MapState[UK,UV]：定义与 key 对应键值对的状态，用于维护具有 key-value 结构类型的状态数据

Flink 中需要通过创建 StateDescriptor 来获取相应 State 的操作类，StateDescriptor 主要定义了状态的名称、状态中数据的类型参数信息以及状态自定义函数，每种 Managed Keyed State 都有对应的 StateDescriptor。
```scala
val env = StreamExecutionEnvironment.getExecutionEnvironment
val input = env.fromElements((2, 21L), (4, 1L), (5, 4L))
input.keyBy(_._1).flatMap(new RichFlatMapFunction[(Int, Long), (Int, Long, Long)] {
  private var state: ValueState[Long] = _


  override def open(parameters: Configuration): Unit = {
    val stateDescriptor = new ValueStateDescriptor[Long]("valueState", classOf[Long])
    state = getRuntimeContext.getState(stateDescriptor)
  }

  override def flatMap(value: (Int, Long), out: Collector[(Int, Long, Long)]): Unit = {
    val stateValue = state.value()
    if (value._2 > stateValue){
      out.collect((value._1, value._2, stateValue))
    }else{
      state.update(value._2)
      out.collect((value._1, value._2, value._2))
    }
  }
})
```
任何类型的 keyed State 都可以设置状态的生命周期(TTL)，以确保能够在规定的时间内及时的清理状态数据。状态生命周期可以通过 StateTtlConfig 设置然后传入 StateDescriptor 的 enableTimeToLive 方法：
```scala
val stateConfig = StateTtlConfig
  // 设置 TTL 时长
  .newBuilder(Time.seconds(10))
  // 只在创建和写入时更新 TTL
  .setUpdateType(StateConfig.UpdateType.OnCreateAndWrite)
  // 数据过期就不再返回
  .setStateVisibility(StateConfig.StateVisibility.NeverReturnExpired)
  .build

val stateDesc = new ValueStateDescriptor[String]("value-state", classOf[Long])
stateDesc.enableTimeToLive(stateConfig)
```
除了通过 RichFlatMapFunction 或者 RichMapFunction 操作状态之外，KeyedStream 提供了 ```filterWithState```, ```mapWithState```, ```flatMapWithState``` 三种方法来定义和操作状态数据：
```scala
val input = env.fromElements((2, 21L), (4, 1L), (5, 4L))
val counts = input.keyBy(_._1)
  .mapWithState((in, count)=>{
    count match{
      case Some(c) => ((in._1, c), Some(c + in._2))
      case None => ((in._1, 0), Some(in._2))
    }
  })
```
##### Managed Operator State
Flink 中可以实现 CheckpointedFunction 或者 ListCheckpointed 两个接口来定义操作 Managed Operator State 操作。

CheckpointedFunction 接口需要实现两个方法：
- initializeState 方法在初始化自定义函数时调用，包括第一次初始化函数和从之前的 checkpoint 中恢复数据
- snapshotState 方法在 checkpoint 触发时会调用

在每个算子中 Managed Operator State 都是以 List 形式存储，算子和算子之间的状态数据相互独立，Flink 支持两种重分布策略：
- Event-split Redistribution：每个算子实例中含有部分状态元素的 List 列表，整个状态数据是所有 List 列表的合集，当触发 restor/redistribution 动作时，通过将状态数据平均分配成算子并行度相同数量的 List 列表，每个 task 实例中有一个 List 可以为空或者含有多个元素
- Union Redistribution：每个算子实例中含有所有状态元素的 List 列表，当触发 restor/redistribution 动作时，每个算子都能够获取到完整的状态元素列表

```scala
class CheckpointCount extends FlatMapFunction[(Int, Long), (Int, Long, Long)] with CheckpointedFunction{

  private var operatorCount: Long = _
  private var keyedState: ValueState[Long] = _
  private var operatorState: ListState[Long] = _

  override def flatMap(value: (Int, Long), out: Collector[(Int, Long, Long)]): Unit = {
    val keyedCount = keyedState.value() + 1
    keyedState.update(keyedCount)
    operatorCount = operatorCount + 1
    out.collect((value._1, keyedCount, operatorCount))
  }

  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    operatorState.clear()
    operatorState.add(operatorCount)
  }

  override def initializeState(context: FunctionInitializationContext): Unit = {
    keyedState = context.getKeyedStateStore.getState(new ValueStateDescriptor[Long]("keyedState", createTypeInformation[Long]))
    operatorState = context.getOperatorStateStore.getListState(new ListStateDescriptor[Long]("operatorState", createTypeInformation[Long]))
    if(context.isRestored){
      operatorCount = operatorState.get().asScala.sum
    }
  }
}
```
ListCheckpointed 接口只支持 List 类型的状态，并且在数据恢复的时候仅支持 envet-redistribution 策略。ListCheckpointed 接口需要实现两个方法：
- snapshotState 方法定义数据元素 List 存储到 checkpoint 的逻辑
- restoreState 方法定义从 checkpoint 中恢复状态的逻辑
```scala
class NumberRecordCount extends FlatMapFunction[(String, Long), (String, Long)] with ListCheckpointed[Long]{
  
  private var numberRecords: Long = 0L
  
  override def flatMap(value: (String, Long), out: Collector[(String, Long)]): Unit = {
    numberRecords += 1
    out.collect((value._1, numberRecords))
  }

  override def snapshotState(checkpointId: Long, timestamp: Long): util.List[Long] = {
    Collections.singletonList(numberRecords)
  }

  override def restoreState(state: util.List[Long]): Unit = {
    numberRecords = 0L
    for (count <- state){
      numberRecords += count
    }
  }
}
```
#### Checkpoint 和 SavePoint
##### Checkpoint 机制
Flink 基于异步轻量级分布式快照技术提供了 Checkpoint 容错机制，分布式快照可以将同一时间点 Task/Operator 的状态数据全局统一快照处理，包括 keyedState 和 Operator State。Flink 会在输入的数据集上间隔地生成 checkpoint barrier，通过 barrier 将间隔时间段内的数据划分到相应的 checkpoint 中，当应用出现异常时，Operator 就能够从上一次快照中恢复所有算子之前的状态，从而保证数据的一致性。checkpoint 过程中状态数据一般被保存在一个可配置的环境中，通常是在 JobManager 节点或 HDFS 上。

默认情况下Flink 不开启 checkpoint，需要调用 enableCheckpointing 方法配置和开启 checkpoint：
```scala
env.enableCheckpointing(1000)
```
可以选择 exactly-once 语义保证整个应用内端到端的数据一致性，通过 ```CheckpointConfig#setCheckpointingMode``` 方法设置语义模式：
```scala
env.getCheckpointConfig().setCheckpointingMode(CheckpointMode.EXACTLY_ONCE)
```
超时时间指定了每次 checkpoint 执行过程中的上限时间范围，一旦超过此阈值，Flink 就会中断 checkpoint 过程并按照超时处理，通过 ```CheckpointConfig#setCheckpointTimeout``` 方法设置超时时间：
```scala
env.getCheckpointConfig().setCheckpointTimeout(60000)
```
检查点之间的间隔可以设置两个 checkpoint 之间的最小间隔，防止出现状态数据过大导致 checkpoint 执行时间过长从而使 Flink 密集的触发 checkpoint 操作，通过 ```CheckpointConfig#setMinPauseBetweenCheckpoints``` 可以设置检查点之间的间隔：
```scala
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(500)
```
通过 setMaxConcurrentCheckpoints 方法可以设置能够同时执行的最大的 checkpoint 数量，默认只有一个 checkpoint 可以运行：
```scala
env.getCheckpointConfig().setMaxConcurrentCheckpoints(2)
```
设置周期性的外部检查点然后将状态数据持久化到外部系统中，使用这种方式不会在任务正常停止的过程中清理掉检查点的数据而是会一直保存在外部系统中，也可以从外部检查点对任务进行恢复：
```scala
env.getCheckpointConfig().enableExternalizedCheckpoints(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)
```
可以设置是否在 checkpoint 执行过程中出现失败或者错误时，任务是否同时被关闭，默认是 false：
```scala
env.getCheckpointConfig().setFailOnCheckpointingErrors(false)
```
##### savepoint 机制
savepoint 是检查点的一种特殊实现方式，底层也是使用 checkpoint 的机制。savepoint 是以手动命令的方式触发 checkpoint 并将结果持久化到指定的存储系统中。主要目的是在升级维护集群过程中保存系统中的状态数据，避免因为停机运维或者升级应用等正常终止应用的操作而导致系统无法恢复到原有的计算状态的情况，从而无法实现端到端的 exactly-once 语义保证。
###### Operator ID 配置
使用 savepoint 对整个集群进行升级或运维操作时，需要停止整个 Flink 应用程序，此时可能会对代码逻辑进行修改，即使 Flink 能够通过 savepoint 机制将应用中的状态数据同步到磁盘然后恢复任务也无法恢复数据。此时需要通过位移的 id 标记算子，Flink 支持自动生成 id 也可以自定义指定 id：
```scala
env.addSource(new StatefulSource())
   // 指定 source 算子的 id
   .uid("source-id")
   .shuffle()
   .map(new StatefulMapper())
   // 指定 Mapper 算子的 id
   .uid("map-id")
   .print()
```
###### 手动触发 savepoint
通过在 Flink 命令中指定 savepoint 关键字触发 savepoint 操作，同时需要在命令中指定 JobId 和 savepoint 数据存储路径
```shell
bin/flink savepoint :jobId [:targetDirectory]
```
在 Hadoop Yarn 上提交的应用，需要指定 Flink jobId 的同时也需要通过使用 yid 指定 YarnAppId
```shell
bin/flink savepoint :jobId [:targetDirectory] -yid :yarnAppId
```
###### 取消任务并触发 savepoint
通过 cancel 命令将停止 Flink 任务的同时自动触发 savepoint 操作并把中间状态数据写入磁盘用以后续的任务恢复
```shell
bin/flink cancel -s [:targetDirectory] :jobId
```
###### 从 savepoint 中恢复任务
使用 run 命令将任务从保存的 savepoint 中恢复
```shell
bin/flink run -s :savepointPath [:runArgs]
```
###### 释放 savepoint 数据
```shell
bin/flink savepoint -d :savepointPath
```
###### 默认 TargetDirectory
savepoint 存储路径可以在命令中设置外，也可以在 flink-conf.yaml 配置文件中配置，参数 ```state.savepoint.dir``` 配置的是 savepoint 的路径，需要是 TaskManager 和 JobManager 能够访问到的路径
```yaml
state.savepoint.dir: hdfs:///flink/savepoinit
```
TargetDirectory 文件路径包含了 savepoint 的目录也包含了 metadata 路径信息：
```
/savepoints
/savepoints/savepoint-:shortjobid-:savepointid/

```
#### 状态管理器
Flink 提供了 StateBackend 来存储和管理 Checkpoints 过程中的状态数据。Flink 一共实现了三种类型的状态管理器：基于内存的 MemoryStateBackend、基于文件系统的 FsStateBackend、基于 RockDB 作为存储介质的 RocksDBStateBackend，默认使用的是基于内存的状态管理器。
##### MemoryStateBackend
基于内存的状态管理器将状态数据全部存储在 JVM 堆内存中，包括 DataStream API 创建的 Key/Value State，窗口中缓存的状态数据，以及触发器等数据。基于内存的状态管理具有非常快速和高效的特定，但是受到内存容量的限制。

MemoryStateBackend 是 Flink 默认的状态管理器，可以在初始化时指定每个状态值最大使用的内存大小：
```scala
new MemoryStateBackend(MAX_MEN_STATE_SIZE, false)
```
##### FsStateBackend
FsStateBackend 基于的文件系统可以是本地文件系统也可以是 HDFS 分布式文件系统，在创建 FsStateBackend 实例时如果是本地文件系统则格式为 ```file:///data/flink/savepoint```，如果是 HDFS 文件系统则格式为 ```hdfs://nameservice/flink/savepoint```
##### RocksDBStateBackend
RocksDBStateBackend 需要引入相关的依赖包到工程，通过初始化 RocksDBStateBackend 可以得到实例。RocksDBStateBackend 采用异步的方式进行状态数据的 snapshot，任务中的状态数据首先被写入 RockDB 中，然后再异步的将状态数据写入文件系统中，这样在 RockDB 仅会存储正在进行计算的热数据，对于长时间才更新的数据则写入磁盘中进行存储。
##### 状态管理配置
Flink 包含了两种级别的 StateBackend 配置：
- 应用层配置：配置的状态管理器只会针对当前应用有效
- 集群配置：配置的状态管理器对整个集群都有效

Flink 应用程序是通过 ```StreamExecutionEnvironment#setStateBackend``` 方法配置状态管理器：
```scala
env = StreamExecutionEnvironment.getExecutionEnvironment
env.setStateBackend(new FsStateBackend("hdfs://namemode:40010/flink/checkpoints"))
```
集群配置是在 flink-conf.yaml 文件中配置：
```yaml
state.backend: filesystem
state.checkpoint.dir: hdfs://namenode:40010/flink/savepoints
```
#### Querable State
Flink 提供可查询的状态服务，可以通过 Flink 提供的 Restful API 接口直接查询 Flink 系统内部的状态数据。Flink 可查询状态服务包含三个组件：
- QueryableStateClinet：作为客户端提交查询请求并收集状态查询结果
- QueryableStateClientProxy：用于接收和处理客户端的请求，每个 TaskManager 上运行一个客户端代理。客户端代理需要通过从 JobManager 中获取 key group 的分布然后判断哪一个 TaskManager 实例维护了 Clinet 中传过来的 key 对应的状态数据，再向所在的 TaskManager 的 server 发送请查询状态值返回给客户端
- QueryableStateServer：用于接收客户端代理的请求，每个 TaskManager 上运行一个 State Server 该 Server 用于通过从本地的状态管理器中查询状态结果，然后返回给客户端代理

##### 激活可查询状态服务
开启可查询服务需要在 Flink 中引入 flink-queryable-state-runtime.jar，Flink 集群启动时 QueryableState 服务就会在 TaskManager 中被拉起，此时就能够处理由 Client 发送的请求。
##### 配置差查询状态服务
激活可查询状态服务后还需要修改 Flink 将需要暴露的可查询状态通过配置开放出来，在创建状态的 StateDescriptor 中调用 setQueryable 方法可以开放

除了通过 StateDescriptor 设置外，Flink 提供了在 DataStream 数据集上来配置可查询的状态数据，通过在 KeyedStream 上使用 asQueryableState 方法可以设置查询状态，返回的 QueryableStateStream 数据集作为 DataSink 算子，因此后面不能再计入其他算子。
##### 查询状态数据
对于基于 JVM 的应用，可以通过 QueryableStateClient 获取 Flink 应用中的可查询状态数据，使用 QueryableStateClient 需要引入依赖
```xml
<dependency>
  <groupId>org.apache.flink</groupId>
  <artifactId>flink-queryable-state-client-java_2.11</artifactId>
  <version>1.8.0</version>
</dependency>
```
```scala
val client = new QueryableStateClient("localhost", 9096)
val vd = new ValueStaetDescriptor[Long]("value-descriptor", TypeInformation.of(new TypeHint[Long](){}))
val result = client.getKvState(JobID.froHexString("jobId"), "queryValue", "key", Type.STRING, vd)
result.thenAccept(response =>{
  try{
    val res = response.value
    println(res)
  }catch{
    case e: Execption => e.printStackTrace()
  }
})
```
### DataSet
DataSet API 用于处理批量数据，使用 DataSet API 需要引入 flink-java 对应的依赖：
```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-scala_2.11</artifactId>
    <version>1.8.0</version>
</dependency>
```
```scala
object WorldCount{
  def main(args:Array[String]){
    val env = ExecutionEnvironment.getExecutionEnvironment
    val text = env.fromElement("hello world", "hello flink")
    val counts = text.flatMap{
      _.toLowerCase.split(" ")
      .filter(_.notEmpty)}
      .map((_, 1))
      .groupBy(0)
      .sum(1)
    counts.print()
  }
}
```
#### DataSource
DataSet 支持从多种数据源中将批量数据集读取到 Flink 系统中并转换成 DataSet 数据集。数据接入有三种类型：文件系统、Java Collection 和通用数据源。还可以实现 InputFormat/RichInputFormat 接口自定义数据源。
##### 文件系统数据源
- readTextFile：读取文本文件并将文件内容转换成 DataSet[String] 类型数据集
- readTextFileWithValue：读取文本文件并将文件内容转换成 DataSet[StringValue] 类型数据集，StringValue 是一种可变的 String 类型
- readCsvFile：根据指定分隔符切割 CSV 文件且可以直接转换成 Tuple 类型、case class 对象或 pojo 对象
- readSequece：读取 sequence 类型文件，返回 Tuple 类型
##### 集合数据源
- fromCollection：从数组、List 等集合中创建 DataSet 数据集
- fromElements：从给定的元素序列中创建 DataSet 数据集
- generateSequence：指定 from 到 to 范围区间然后在区间内部生成数字序列数据集
##### 通用数据源
- readFile：自定义文件类型输入源，将指定格式文件读取并转换成 DataSet 数据集
- createInput：自定义通用类型数据，将读取的数据转换为 DataSet 数据集
#### DataSet 转换
Flink 提供了非常丰富的转换操作，从而实现了基于 DataSet 批量数据集的转换，转换操作实质是将 DataSet 转换成另一个新的 DataSet 然后将各个 DataSet 的转换连接成有向无环图并基于 DAG 完成对批量数据的处理。
##### 数据处理
- Map：将每一条数据转换成新的一条数据，数据分区不发生变化
- FlatMap：将接入的每一条数据转换成多条数据输出
- MapPartition：基于分区对数据进行 Map 处理
- Filter：根据传入的条件进行过滤，满足条件的数据元素才会传输到下游 DataSet 中
##### 聚合操作
- Reduce：通过两两合并，将数据集中的元素合并成一个元素
- ReduceGroup：将一组元素合并成一个或多个元素
- Aggregate：将一组元素值合并成单个值
- Distinct：去除数据集合中的重复元素
##### 多表关联
- Join：根据指定条件关联两个数据集
- OuterJoin：对两个数据集进行外关联，包含 left, right, full outer join 三种关联方式，分别对应 leftOuterJoin, rightOuterJoin, fullOuterJoin 方法
- Cogroup：将两个数据集根据相同的 key 记录组合在一起，相同 key 的记录会存放在一个 group 中
- Cross：将两个数据集合并成一个数据集，返回被连接的两个数据集所有数据行的笛卡尔积
##### 聚合操作
- Union：合并两个 DataSet 数据集，两个数据集的数据元素必须相同，多个数据集合可以连续合并
- Rebalance：对数据集中的数据进行平均分布使得每个分区上的数据量相同
- HashPartition：根据给定的 key 进行 Hash 分区，key 相同的数据会进入同一个分区
- RangePartition：根据给定的 key 进行 Rnage 分区，key 相同的数据会进入同一个分区
- SortPartition：在本地对 DataSet 数据集中的所有分区根据指定字段进行重排序
##### 排序操作
- First：返回数据集的 n 条随机结果
- Minby/Maxby：从数据集中返回指定字段或组合对应最小或最大的记录
#### 数据输出
Flink 的数据输出全部实现了 OutputFormat 接口，内置的数据输出分为三类：基于文件实现、基于通用存储介质实现、客户端输出
##### 基于文件输出
- writeAsText：将DataSet 数据以 TextOutputFormat 文本格式写入文件系统，可以是本地文件系统也可以是 HDFS 文件系统
- writeAsCsv：将数据集以 CSV 文件格式输出到指定的文件系统中
##### 通用输出
使用自定义的 OutputFormat 将数据集输出到指定的介质：
```scala
words.output(hadoopOutputFormat)
```
#### 迭代计算
Flink 由两种迭代计算模式：全量迭代(Bulk Iteration) 和增量迭代(Delt Iteration)
##### 全量迭代
在数据接入迭代算子过程中，StepFunction 每次都会处理全量的数据，然后计算下一次迭代的输入，最后根据触发条件输出迭代计算的结果并将结果通过 DataSet API 传输到下一个算子中继续进行计算。

Flink 中迭代的数据不是通过在迭代计算中不断生成新的数据集完成，而是基于同一份数据集上完成迭代计算操作，因此不需要对数据集进行大量的拷贝复制操作，避免数据在复制过程中所导致的性能下降问题。

全量迭代计算分为几个步骤：
- 初始化数据，可以通过从 DataSource 算子中读取也可以从其他转换 Operator 中接入
- 定义 StepFunction 并在每一步迭代过程使用 StepFunction 结合数据集及上次迭代计算的结果数据集进行本地迭代
- 将最后一次迭代的数据输出，可以通过 DataSink 输出或者接入下一个 Operator 中。迭代终止条件有两种：
  - 最大迭代次数：指定迭代的最大次数，当计算次数超过该设定值着终止迭代
  - 自定义收敛条件：通过自定义的聚合器和收敛条件，当达到收敛条件时终止迭代
```scala
val env = ExecutionEnvironment.getExecutionEnvironment
val initial = env.fromElements(0)
val count = initial.iterate(10000){ iterationInput =>
    val result = iterationInput.map{ i=>
      val x = Math.random
      val y = Math.random
      i + (if(x * x + y * y < 1) 1 else 0)
    }
    result
  }
  val result = count map {c => c / 10000.0 * 4}
  result.println()
  env.execute("Iterative Pi")
}
```
##### 增量迭代
增量迭代在计算的过程中会将数据集分为热点数据集和非热点数据集，每次迭代计算会针对热点数据展开，不需要对全部的输入数据集进行计算。

```scala

```
#### 广播变量与分布式缓存
##### 广播变量
广播变量目的是对小数据集采用网络传输的方式，在每个并行计算节点的实例内存中存储一份该数据集，所有的计算节点实例可以在本地内存中直接读取广播的数据集，这样能够避免在数据计算过程中多次通过远程的方式从其他节点中读取小数据集。

DataSet API 中，广播变量通过 withBroadcastSet 方法定义，其中第一个参数为所需要广播的 DataSet 数据集，需要保证在广播前创建完毕；第二个参数为广播变量的名称，需要在当前应用中保持唯一：
```scala
def withBroadcastSet(data: DataSet[_], name: String)
```
DataSet API 支持在 RichFunction 接口通过 RuntimeContext 读取到广播变量：
```scala
dataSet.map(new RichMapFunction[String, String](){
  var broadcastSet: Traversable[Int]
  override def open(config: Configuration):Uint={
    broadcastSet = getRuntimeContext().getBroadcastVariable("broadcastName").asScala
  }
})
```
##### 分布式缓存
对于高频使用的文件可以通过分布式缓存的方式将其放置在每台计算节点实例的本地 task 内存中，这样能够避免因为读取某些文件而必须通过网络远程获取文件的情况，进而提升整个任务的执行效率。

分布式缓存在 ExecutionEnvironment 中直接注册文件或文件夹，Flink 在启动任务的过程中将会把指定的文件同步到 task 所在的计算节点的本地文件系统中：
```scala
val env = ExecutionEnvironment.getExecutionEnvironment
env.registerCachedFile("hdfs:///path/file", "hdfsFile")

env.registerCachedFile("file:///path/file", "localFile", true)
```
获取缓存文件和广播变量相似，通过实现 RichFucntion 接口并获取 RuntimeContext 对象，然后通过 RuntimeContext 对象获取对应的缓存文件：
```scala
class FileMapper extends RichMapFunction[String, Int]{
  var myFile: File = null
  override def open(config: Configuration):Uint={
    myFile = getRuntimeContext.getDistributedCache.getFile("hdfsFile")
  }
}
```
#### 语义注解
Flink 提供语义注解功能，将传入 Function 但没有参与实际计算的字段在 Function 中通过注解的形式标记起来，区分哪些是需要参与函数计算的字段，哪些是直接输出的字段。Flink Runtime 在执行算子过程中会对注解的字段进行判断，对于不需要函数处理的字段直接转发到 Output 对象中以减少数据传输过程中所消耗的网络 IO 或者不必要的排序操作等，以提升整体应用的处理效率。

DataSet API 将语义注解支持的字段分为三种类型：Forwarded Fields、NonForward Fields 和 Read Fields
##### Forwarded Fields
转发字段(Forwarded Fields) 代表数据从 Function 进入后，对指定为转发的字段不进行修改，且不参与函数的计算逻辑，而是根据设定的规则表达式将 Fields 直接推送到 Output 对象中的相同位置或指定位置上。
使用注解实现 是通过 @ForwardedFields 注解用于单输入的 Function 进行字段转发：
```scala
@ForwardedFields("_1->_2")
class MyMapper extends MapFunction[(Int, Double), (Double, Int)]{
  def map(t:(Int, Double)):(Double, Int)={
    return (t._2/2, t._1)
  }
}
```
使用算子参数方式实现是通过在 Operator 算子中调用 withForwardFields 完成函数的转发字段的定义：
```scala
dataSet1.join(dataSet2).where("id").equalTo(1){
  (left, right, collector:Collector[(String, Double, Int)])=>
    collector.collect(left.name, right._1 + 1, right._2)
    collector.collect("prefix_" + left.name, right._1 + 2, right._2)
}.withForwardFieldsSecond("_2->_3")
```
##### NonForward Fields
NonForward Fields 用于指定不转发的字段，也就是说除了某些字段不转发在输出 Tuple 相应的位置上，其余字段全部防止在输出 Tuple 中相同的字段位置上，对于被 NonForward Fields 指定的字段将必须参与到函数计算的过程中并产生新的结果进行输出。
```scala
@NonForwardFields("_2")
class MyMapper extends MapFunction[(String, Long, Int),(String, Long, Int)]{
  def map(input: (String, Long, Int)):(String, Long, Int)={
    return (input._1, input._2/2, input._3)
  }
}
```
##### Read Fields
读取字段(ReadFields)用来指定 Function 中需要读取以及参与函数计算的字段，在注解中被指定的字段将全部参与当前函数结果的运算过程。
```scala
@ReadFields("_1;_2")
class MyMapper extends MapFunction[(Int, Int, Double, Int),(Int, Long)]{
  def map(value:(Int, Int, Double, Int)):(Int, Double)={
    if (value._1 == 4){
      return (value._1, value._3)
    }else{
      return (value._2 + 10, value._3)
    }
  }
}
```
### Table API
#### TableEnvironment
TableEnvironment 是 Table API 和 SQL 的编程环境，需要引入 Maven 依赖：
```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-table_2.11</artifactId>
    <version>1.8.0</version>
</dependency>
```
使用 Table API 或者 SQL 创建 Flink 应用程序需要在环境中创建 TableEnvironment 对象，TableEnvironment 提供了注册内部表、执行 Flink SQL 语句、注册自定义函数等功能。
```scala
// 流式应用
val env = StreamExecutionEnvironment.getExecutionEnvironment
val t = TableEnvironment.getTableEnvironment(env)

// 批处理应用
val env = ExecutionEnvironment.getExecutionEnvironment
val t = TableEnvironment.getTableEnvironment(env)
```
##### CataLog 注册
获取到 TableEnvironment 对象之后可以使用 TableEnvironment 提供的方法注册相应的数据源和数据表信息。所有的数据库和表的元数据信息存放在 Flink CataLog 内部目录结构中。

通过 TableEnvironment 的 Register 接口完成对数据表的注册：
```scala
val e = StreamExecutionEnvironment.getExecutionEnvironment
val env = TableEnvironment.getTableEnvironment(e)
// 通过 select 查询生成 table
val table = env.scan("SensorsTable").select(...)
// 将 table 在 Catlog 中注册成内部表 projectedTable
env.registerTable("projectedTable", table)
```
完成对 Table 的注册在内部 CataLog 生成相应的数据表信息就可以使用 SQL 语句对表进行处理完成数据转换操作。注册在 CataLog 中的 Table 类似关系数据库中的视图结构，当注册的表被引用和查询时数据才会在对应的 Table 中生成。多个语句同时查询一张表时，表中的数据将会被执行多次，且每次查询出的结果互相之间不共享。

使用 Table API 时，可以将外部的数据源直接注册成 Table 数据结构，除了 Flink 内置的 Source 外，可以通过 TableSource 自定义数据源注册成 Table。
```scala
val env = StreamExecutionEnvironment.getExecutionEnvironment
val tableEnv = TableEnvironment.getTableEnvironment(env);
```
### Flink 监控调优
### Flink & Kafka