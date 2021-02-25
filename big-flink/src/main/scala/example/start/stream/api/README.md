## DataStream

Flink 基于 DataFlow 模型实现了支持原生数据流处理的计算引擎，Flink 定义了 DataStream API 让用户灵活且高效地编写 Flink 流式应用。

DataStream API 分为三部分：
- Source：定义数据接入功能，将外部数据接入到 Flink 系统中，并将接入的数据转换成对应的 DataStream 数据集
- Transform：定义了对 DataStream 数据集的各种转换操作
- Sink：定义将转换后的数据集导出到外部存储介质

### DataSource
Flink 将数据源主要分为内置数据源和第三方数据源两种类型，内置数据源包含文件、Socket 以及集合类型，第三方数据源定义了 Flink 和外部系统数据交互逻辑，包括数据的读写接口，用户也可以实现 SouceFunction 自定义数据源。

#### 文件数据源
Flink 支持将文件内容读取到系统中，并转换成 DataStream 进行数据处理。使用 `StreamExecutionEnvironment` 的 readTextFile 方法可以直接读取文本文件，也可以通过 readFile 通过指定文件的 InputFormat 来读取特定格式的文件数据。
```scala
// 读取文本文件
val textStream = env.readTextFile("file/path")
// 读取特定数据格式的文件
val csvStream = env.readFile(new CsvInputFormat[String](new Path("csv/file/paht")){
  override def fillRecord(out: String, objects: Any[AnyRef]): String = {
  
}
})
```
在 DataStream API 中可以在 readFile 方法中指定文件读取类型(watchType)，检测时间变化间隔(interval)，文件路径过滤(FilePathFilter) 等参数。

watchType 由两种可取的值：
- PROCESS_CONTINUOUSLY：一旦检测到文件内容发生变化就会将文件全部内容加载到 Flink 系统处理
- PROCESS_ONCE：当文件内容发生变化时，只会将变化的数据加载到 Flink 系统中处理

#### Socket
Flink 支持从 Socket 接入数据，通过 StreamExecutionEnvironment 的 socketTextStream 方法传入 ip，端口，分隔符以及最大尝试次数即可。
```scala
val socketStream = env.socketTextStream("localhost", 9999)
```
在 Linux 系统中通过 `nc -lk 9999` 命令可以启动 Socket 客户端向 Flink 系统发送数据


#### 集合数据源
Flink 可以直接将 Java 或者 Scala 中的集合类转换成 DataStream，本质上时将本地集合中的数据分发到远端并执行的节点中。

```scala
// 通过 fromElements 从数据集中创建 DataStream
val dataStream = env.fromElements(Tuple2(1L, 3L), Tuple2(1L, 5L))
// 通过 fromCollection 从集合中创建 DataStream
val collectionStream = env.fromCollection(Arrays.asList("hello", "flink"))
```

#### 数据源连接器
Flink 从外部系统接入数据需要通过数据源连接器完成，Flink 通过实现 SourceFunction 定义了丰富的第三方数据连接器，在使用时需要导入依赖。
```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-connection-kafka</artifactId>
</dependency>
```
````scala
Properties properties = new Properties();
properties.setProperty("bootstrap.servers", "localhost:9090")
properties.setProperty("group.id", "test")

val stream = env.addSource(new FlinkKafkaConsumer("topic", new SimpleStringSchema(), properties))
````
可以自定以 Schema 将接入的数据转换成特定的数据结构，通过实现 DeserializationSchema 接口完成自定义的类型转换

Flink 内部提供了常用的序列化协议的 Schema，如 TypeInformationSerializationSchema， JsonDeserializationSchema 和 AvroDeserializationSchema 等。

Flink 实现了大多数主流的数据源连接器，用户也可以自己定义连接器以满足不同的数据源接入需求。可以通过实现 SourceFunction 定义单个线程的接入器，也可以通过实现 ParallelSourceFunction 接口或者继承 RichParallelSouceFucntion 定义并发数据接入器。

DataSource 定义后可以通过 StreamExecutionEnvironment 的 addSource 方法添加数据源，这样就可以将外部系统中的数据转换成 DataStream

### Transformation

Flink 中将一个 DataStream 转换成新的 DataStream 的过程称为 Transformation，在转换的过程中，每种操作类型定义为不同的 Operator。

Flink 能够将多个 Transformation 组成一个 DataFlow 的拓扑。所有的 DataStream 转换操作可以分为 Single-Stream，Multi-Stream 和物理分区三种类型，Single-Stream 定义了对单个 DataStream 的元素处理逻辑，Multi-Stream 定义了对多个 DataStream 数据的处理逻辑，物理分区定义了对数据集中的并行度和数据分区调整转换的处理逻辑。

#### Single-DataStream 操作

MapFunction 将 DataStream 转换成新的 DataStream，转换过程中数据格式可能会发生变化，常用作对数据集内数据的清洗和转换。
```scala
val dataStream = env.fromElements(("a", 3), ("d", 4), ("c", 2), ("c", 5))

val mappedStream = dataStream.map(t => (t._1, t._2 + 1))
```
通过实现 MapFunction 或者继承 RichMapFunction 可以自定义 map 逻辑
```scala
val mappedStream = dataStream.map(new MapFunction[(String, Int), (String, Int)]{
  override def map(t: (String, Int)): (String, Int) ={
    (t._1, t._2 + 1) 
}
})
```

FlatMapFunction 将 DataStream 转换成新的 DataStream，应用于处理输入一个元素产生一个或者多个元素的计算场景
```scala
val flatMappedStream = dataStream.flatMap{ str => str.split(" ")}
```

FilterFunction 将 DataStream 转换成新的 DataStream，该算子按照条件对输入的数据集进行过滤操作，过滤掉不符合条件的数据。
```scala
val filteredStream = dataStream.filter(_ % 2 == 0)
```

keyBy 方法根据指定的 key 将 DataStream 转换为 KeyedStream，也就是在数据集中执行 Partition 操作，将相同的 Key 值的数据放置在相同的分区中。
```scala
val keyedStream = dataStream.keyBy(0)
```

ReduceFunction 将输入的 KeyedStream 进行增量聚合处理，ReduceFunction 必须满足运算结合律和交换律。
```scala
val keyedStream = dataStream.keyBy(0)

val reduceStream = keyedStream.reduce{(t1, t2) => (t1._1, t1._2 + t2._2)}

val reduceFuncStream = keyedStream.reduce(new ReduceFunction[(String, Int)]{
  override def reduce(t1:(String, Int), t2: (String, Int)):(String, Int)={
    (t1._1, t1._2 + t2._2)
  } 
}) 
```

AggregateFunction 将 KeyedStream 转换为 DataStream，根据指定的字段进行聚合操作，滚动地产生一系列数据聚合的结果。

Flink 内置了多个聚合算子，包括 sum, min, minBy, max, maxBy 等
```scala
val keyedStream = dataStream.keyBy(0)

val sumStream = keyedStream.sum(1)
```

#### Multi-DataStream 操作

union 算子能够将多个 DataStream 合并成一个 DataStream，需要保证两个数据集的格式一致，输出数据集的格式和输入数据集的格式保持一致。
```scala
val stream1 = env.fromElements(("a", 3), ("b", 4))
val stream2 = env.fromElements(("d", 1), ("s", 2))
val stream3 = env.fromElements(("c", 3), ("b", 1))

val unionStream = stream1.uion(stream2, stream3) 
```

connect 算子可以将多种不同数据类型的 DataStream 合并成 ConnectedStream，合并后保留原来数据集的数据类型。connect 操作允许共享状态数据，也就是说在多个数据集之间可以操作和查看对方数据集的状态。
```scala
val stream1 = env.fromElements(("a", 3), ("b", 2))
val stream2 = env.fromElements(1, 4, 6)

val connectdStream: ConnectedStream[(String, Int), Int] = stream1.conect(stream2)
```
ConnectedStream 需要通过 CoMapFunction 或者 CoFlatMapFunction 处理输入的 DataStream
```scala
val resultStream = connectedStream.map(new CoMapFunction[(String, Int), Int, (Int, String)]{
  
// 定义第一个数据集的处理逻辑  
override def map1(in1:(String, Int)):(Int, String)={
  (in1._2, in1._1)
  }

// 定义第二个数据集的处理逻辑
override def map2(in2: Int):(Int, String)={
  (in2, "default")
}
})

val resultStream2 = connectedStream.flatMap(new CoFlatMapFunction[(String, Int), Int, (String, Int, Int)] {
  // 定义共享变量
  var number = 0
  // 定义第一个数据集处理函数
  override def flatMap1(in1:(String, Int), collector: Collector[(String, Int, Int)]):Unit={
    collector.collect((in1._1, in1._2, number))
  }
  // 定义第二个数据集处理函数
  override def flatMap2(in2:Int, collector: Collector[(String, Int, Int)]):Uint ={
    number = in2
  }
})
```
CoMapFunction 和 CoFlatMapFunction 中定义的两个函数会多线程交替执行，最终将两个数据集根据定义合并成目标数据集。

通常情况下数据集的合并会在指定的条件下进行关联，然后产生相关性比较强的结果数据集，通过 keyBy 函数或者 broadcast 广播可以实现。
```scala
// 通过 keyBy 根据指定的 key 连接两个数据集
val keyedConnect: ConnectedStream[(String, Int), Int] = dataStream1.connect(dataStream2).keyBy(1, 0)
// 通过 broadcast 关联两个数据集
val broadcaseStream: BroadcastConnectedStream[(String, Int), Int] = dataStream1.connect(dataStream2.broadcast())
```
通过使用 keyBy 会将相同的 key 的数据路由到同一个 算子中，而 broadcast 会在执行计算逻辑之前将数据集广播到所有并行计算的算子中，这样就能够根据条件对数据集进行关联。

Iterate 算子适合于迭代计算场景，通过每一次的迭代计算，并将计算结果反馈到下一次迭代计算中。
```scala
val iteratedStream = dataStream.iterate((intput: ConnectdStream[Int, String])=>{
  // 定义迭代逻辑
  val head = input.map(i => (i+1).toString, s=>s)
  (head.filter(_ == "2", head.filter(_ != "2")))
}, 1000)
```

### 物理分区操作

物理分区操作的作用是根据指定的分区策略将数据重新分配到不同节点的 Task 上执行，当使用 DataStream 提供的 API 对数据处理过程中依赖算子本身对数据的分区控制，如果希望自己控制数据分区(例如发生数据倾斜)就需要通过定义物理分区策略对数据集进行重新分布处理。

Flink 提供了常见的分区策略，包括 RandomPartition, RoundRobinPartition 以及 RescalingPartition，初次之外还可以自定义数据分区的方式。

RandomPartition 将数据随机分配到下游算子的每个分区中，分区相对均衡，但是叫容易失去原有数据的分区结构。
```scala
val shuffleStream = dataStream.shuffle
```
RoundRobinPartition 通过循环的方式对数据集中的数据进行重分区，能够尽可能保证每个分区的数据平衡，当数据集发生数据倾斜的时候使用这种策略是比较有效的优化方法。
```scala
val shuffleStream = dataStream.rebalance()
```
RescalingPartition 也是通过循环的方式进行数据重平衡的分区策略，RoundRobinPartition 时数据会全局性地通过网络介质传输到其他地节点完成数据地重新平衡，而 RescalingPartition 仅仅对上下游继承地算子数据进行重平衡，具体地分区主要根据上下游算子地并行度决定。
```scala
val shuffleStream = dataStream.rescale()
```
广播(broadcast)将输入的数据集复制到下游算子的并行的 Task 实例中，下游算子的 Task 可以直接从本地内存中获取广播数据集而不再依赖网络传输，这种方式适合大数据集关联小数据集时将小数据集以广播的方式分发到算子的每个分区中。
```scala
val shuffleStream = dataStream.broadcast()
```
除了已有的分区器外，还可以自定义分区器，然后调用 DataStream API 的 partitionCustom() 方法将创建的分区器应用到数据集上。
```scala
object customPartitioner extends Partitioner[String]{
  val r = scala.util.Random
  override def partition(key: String, numberPartitions: Int):Int ={
    if (key.contains("flink")) 0 else r.nextInt(numberPartitions)
  }
}

dataStream.partitionCustom(customPartitioner, 0)
```

## DataSink
DataStream 中的数据经过转换后形成最终的结果集，通常情况下需要将结果集输出到外部系统中存储，Flink 将 DataStream 中的数据输出到外部系统的过程称为 DataSink。

Flink 内置了基本的数据输出，包括文件输出、客户端输出、Socket 输出等。
```scala
val personStream = env.fromElements(("peter", 18), ("tom", 32))
// 以 overwrite 的方式输出到 csv 文件
personStream.writeAsCsv("file:///csv/file/path", WriteMode.OVERWRITE)
// 输出到本地文件系统
personStream.writeAsText("file:///text/file/path")
// 输出到指定的 Socket 端口
personStream.writeToSocket("host", 9999, new SimpleStringSchema())
```
Flink 提供的基本数据输出方式不能完全满足现实场景的需要，Flink 提供了 DataSink 算子处理数据输出，所有的数据输出都可以基于 SinkFunction 定义。
```scala
val wordStream = env.fromElements(1, 2, 3, 4)
val kafkaProducer = new FlinkKafkaProducer[String](
  "localhost:9092", "kafka-topic", new SimpleStringSchema())
wordStream.addSink(kafkaProducer)
```