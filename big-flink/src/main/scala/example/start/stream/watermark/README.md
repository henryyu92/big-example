## Watermark

流式数据的特定是数据具有时间属性，Flink 根据时间产生的位置不同，将时间区分为为三种概念：
- 事件时间(EventTime)：数据产生时生成的时间，在数据接入 Flink 之前存在，具有不变性
- 接入时间(IngestionTime)：数据接入 Flink 时产生的时间，依赖于 Source 算子所在机器的系统时间，数据接入之后不会发生变化
- 处理时间(ProcessTime)：数据在 Flink 算子中执行转换时的时间

Flink 中默认使用的时 ProcessTime，如果需要指定使用其他时间语义，可以在 `StreamExecutionEnvironment` 中通过 setStreamTimeCharacteristic 方法指定系统全局的时间语义
```scala
val env = StreamExecutionEnvironment.getExecutionEnvironment()
// 指定系统时间语义
val.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
```

Watermark 是一种衡量 EventTime 进展的机制，可以设定延迟触发窗口计算，数据流中的 Watermark 表示 timestamp 小于 Watermark 的数据都已经到达。

Watermark 是一条特殊的数据记录，必须单调递增以确保事件时间在向前推进，Watermark 与数据的时间戳相关。

由于事件时间是数据接入系统前产生，受网络的影响，数据并不能按照数据产生的顺序接入系统，Flink 提供了 Watermark 机制表明当前数据处理的进度，也就是数据到达的完整性，保证数据到达 Flink 系统。

Watermark 机制只支持事件事件，因此在使用 Watermark 时需要指定表示 EventTime 的字段信息，Flink 根据字段信息抽取出事件时间。Watermark 表示在此之前的数据已经收到，因此需要指定 Watermark 的生成机制，Flink 支持两种方式指定事件时间以及生成 Watermark
- 在 DataStream Source 算子结果的 Source Function 中定义
- 自定义 Timestamp Assigner 和 Watermark Generator 生成

数据在进入 Flink 系统时就直接分配 EventTime 和 Watermark，需要复写 SouceFunction 的 run 方法实现数据接入逻辑，并通过 collectWithTimestamp 方法定义 EventTime 以及通过 emitWatermark 方法生成 Watermark

```scala
val input = List(("a", 1L, 1), ("b", 1L, 1), ("b", 3L, 1))
val source = 
```
如果已经定义了数据接入的逻辑，则就需要借助 TimestampAssigner 来管理数据流中的 Timestamp 元素和 Watermark

TimestampAssigner 一般在 DataSource 算子后面，也可以在后续算子中指定，但是需要在第一个时间相关的算子之前，通过 Timestamp Assigner 定义的逻辑会覆盖在 Source 算子中定义的逻辑。

Flink 定义了两种类型的 Watermark 生成方式
- AssignerWithPeriodicWatermarks：根据指定时间间隔周期性的生成 Watermark
- AssignerWithPunctuatedWatermarks：根据接入的数据数量生成 Watermark

Flink 实现了两种 PeriodicWatermark，一种是升序模式，将数据中的 Timestamp 根据指定字段提取，并利用最新的 Timestamp 作为 Watermark
```scala
val in = env.fromCollection(List(("a", 1L, 1), ("b", 1L, 1), ("b", 3L, 1),("a", 2L, 2)))
// 指定 Timestamp 字段并生成 Watermark
in.assignAscendingTimestamps(t => t._3)
  .keyBy(0)
  .timeWindow(Time.seconds(10))
  .sum(2)
  .print
    
```
另外一种是设置固定时间间隔来指定 Watermark 落后 timestamp 的间隔，也就是最多容忍数据迟到的时间。
```scala
// 指定 timestam 并生成 Watermark
in.assignTimestampsAndWatermarks(
  new BoundedOutOfOrdernessTimestampExtractor[(String, Long, Int)](Time.seconds(10)) {
      // 定义时间戳抽取逻辑，如果数据时间大于 Watermark 时间则认为是超时数据
      override def extractTimestamp(element: (String, Long, Int)): Long = {
        element._2
      }
    })
```
通过实现 AssignerWithPeriodicWatermarks 和 AssignerWithPunctuatedWatermarks 接口可以自定义时间信息的指定以及生成 Watermark

在实现 AssignerWithPeriodicWatermarks 之前需要在 ExecutionConfig 中调用 setAutoWatermarkInterval 方法设置 Watermark 产生的时间周期
```scala
ExecutionConfig.setAutoWatermarkInterval()
```
实现 AssignerWithPeriodicWatermarks 接口需要复写 extractTimestamp 方法实现定义 timestamp 抽取逻辑，复写 getCurrentWatermark 实现 Watermark 生成逻辑
```scala
class PeriodicAssigner extends AssignerWithPeriodicWatermarks[(String, Long, Int)]{
  val maxOutOfOrder = 1000L
  var currentMaxTimestamp : Long = Long.MinValue
  // 定义生成 watermark 逻辑
  override def getCurrentWatermark: Watermark = {
    // 延迟在 maxOutOrder 内的数据认为是可以接受的
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

AssignerWithPunctuatedWatermarks 接口可以根据特殊条件生成 Watermark
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

Watermark 在算子之间的传递是通过广播的方式，Flink 算子内部维护了不同分区的 Watermark 并将其中的最小值作为当前算子的 Watermark 传递出去。

Watermark 在设置时间语义时，默认生成 Watermark 的周期为 200 ms