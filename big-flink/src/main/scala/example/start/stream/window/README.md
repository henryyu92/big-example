## 窗口计算

窗口是将无限流切割为有限流的一种方式，将流数据分发到有限大小的桶中分析。



Flink DataStream 将窗口抽象成独立的 Operator，每个窗口算子中包含 `WindowsAssigner`、`WindowsTrigger`、`Evictor`、`Lateness`、`OutputTag` 以及 `WindowFunction` 等组件，其中 `WindwosAssigner` 和 `WindowsFunction` 必须指定，其他组件按需指定。

- `WindowsAssigner`：指定窗口的类型，定义如何将数据流分配到不同的窗口
- `WindowsTrigger`：指定窗口触发计算的条件
- `Evictor`：指定从窗口中剔除数据的条件
- `Lateness`：标记是否处理迟到数据，以及迟到数据到达时是否触发计算
- `OutputTag`：标记标签，然后通过 OutSideOutput 将窗口中的数据根据标签输出

### `WindowsAssigner`

Flink 提供了两类窗口类型
- 时间窗口
    - 滚动时间窗口(TumblingTimeWindow)
    - 滑动时间窗口(SlidingTimeWindow)
    - 会话窗口(Session Windows)
- 计数窗口
    - 滚动计数窗口(TumblingCountWindow)
    - 滑动计数窗口(SlidingCountWindow)

在窗口计算时，如果数据集是 KeyedStream 类型，则通过 `window()` 方法指定 `WindowsAssigner` 会将数据根据 Key 在不同的 Task 中分别计算，最后针对每个 Key 计算结果；如果是 Non-Keyed 类型，则通过 `windowsAll()` 方法指定的 `WindowsAssigner` 将所有的数据路由到一个 Task 中计算并得到计算结果。
```scala
val keyedStream:KeyedStream[];

keyedStream.

```
Flink 支持基于时间的窗口和基于数量的窗口。

在 Flink 流式计算中，通过 WindowAssigner 将接入的数据分配到不同的窗口，根据数据分配的不同方式，Flink 中基于时间的窗口可以分为 4 类：
- 滚动窗口(Tumbling Windows)
- 滑动窗口(Sliding Windows)
- 会话窗口(Session Windows)
- 全局窗口(Global Windows)

滚动窗口根据固定时间或大小进行切分，且窗口和窗口之间的元素互相不重叠，Flink 提供了基于事件时间和基于处理时间的滚动窗口，对应的 WindowAssigner 分别为 TumblingEventTimeWindows 和 TumblingProcessTimeWindows，调用 DataStream 的 window 方法指定使用的 WindowAssigner
```scala
var stream: DataStream[T]

val eventTimeTumblingWindowStream = stream.keyBy(_.1)
    .window(TumblingEventTimeWindows.of(Time.second(10)))

val processTimeTumblingWindowStream = streama.keyBy(_.1)
    .window(TumblingProcessingTimeWindows.of(Time.second(1)))
```
默认窗口时间的时区是 UTC-0 因此在其他时区需要考虑时差

滑动窗口增加了滑动时间，并且允许窗口数据发生重叠，滑动窗口根据滑动时间滑动，窗口之间的重叠数据由窗口大小和滑动时间决定
```scala
var stream: DataStream[T]

val slidingEventTimeWindowStream = stream.keyBy(_.1)
    .window(SlidingEventTimeWindows.of(Time.second(1)))

val slidingProcessingTimeWindowStream = stream.keyBy(_.1)
    .window(SlidingProcessingTimeWindows.of(Time.second(1)))
```
会话窗口将某段时间内活跃度较高的数据聚合成一个窗口进行计算，窗口的触发条件是 Session Gap，也就是在规定的时间内没有活跃的数据接入则认为窗口结束，然后触发窗口计算。
```scala
var stream: DataStream[T]

val eventTimeSessionWindowStream = stream.keyBy(_.1)
    .window(EventTimeSessionWindows.withGap(Time.second(1)))

val processingTimeSessionWindowStream = stream.keyBy(_.1)
    .window(ProcessingTimeSessionWindows.withGap(Time.second(10)))
```
通过 SessionWindowTimeGapExtractor 能够动态的调整会话窗口 Session Gap
```scala
var stream: DataStream[T]

val eventTimeSessionWindowStream = stream.keyBy(_.1)
    .window(EventTimeSessionWindows.withDynamicGap(new SessionWindowTimeGapExtractor[String] {
      override def extract(element: String): Long = {
        // ...
      }
    }))
```
### 全局窗口
全局窗口(Global Windows)将所有相同的 key 的数据分配到单个窗口中计算结果，窗口没有起始和结束时间，窗口需要借助 Trigger 来触发计算，如果没有指定 Trigger 则不会触发窗口的计算，使用全局窗口时还需要指定相应的数据清理机制，否则数据将一直留在内存中。
```scala
var stream: DataStream[T]

var globalWindow = stream.keyBy(_.id).window(GlobalWindows.create())
```

## 窗口函数

窗口函数定义了对窗口中的数据进行的操作，窗口函数分为两类：
- 增量聚合函数(incremental aggregation functions)：函数中保存着一个状态，每条数据接入就会触发计算，Flink 提供了 ReduceFunction 和 AggregateFunction
- 全窗口函数(full window functions)：数据接入窗口后会缓存起来，等到窗口触发计算时再计算所有的数据，Flink 提供了 ProcessWindowFunction 和 WindowFunction

Flink 提供了四种类型的窗口函数，窗口函数分为增量聚合函数和全量窗口函数，增量聚合函数基于中间状态的计算结果，窗口中只维护中间结果状态值，不需要缓存原始数据；全量窗口函数需要对所有属于该窗口的接入数据进行缓存，然后在窗口触发的时候对所有的数据进行计算。
### ReduceFunction
ReduceFunction 定义了对输入的两个相同类型的数据元素按照指定的计算方法进行聚合的逻辑，然后输出类型相同的一个结果元素。
```java
public interface ReduceFunction<T> extends Function{ 
  T reduce(T value1, T value2) throws Exception;
}
```
在通过 WindowAssigner 创建窗口之后通过 `reduce()` 方法可以指定 ReduceFunction 定义数据的处理逻辑
```scala
var stream DataStream[T]

val reduceWindowStream = stream.keyBy(_._0).window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(10)))
    .reduce{(v1, v2) -> (v1._1, v1._2 + v2._2)}
```
通过实现 ReduceFunction 接口可以自定义聚合逻辑
```scala
val stream DataStream[T]

val reduceWindowStream = stream.keyBy(_._1).window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(10)))
    .reduce(new ReduceFunction[(Int, Long)]{
      override def reduce(t1: (Int, Long), t2: (Int, Long)): (Int, Long) = {
        (t1._1, t1._2 + t2._2) 
    } 
})
```
### AggregateFunction
AggregateFunction 也是基于中间状态计算的增量计算函数，但是接口更加灵活，实现复杂度也相对较高。AggregateFunction 接口定义了三个方法：
```java
public interface AggregateFunction<IN, ACC, OUT> extends Function{
    
    ACC createAccumulator();

    // 定义数据添加逻辑
    ACC add(IN value, ACC accumulator);

    // 定义获取计算结果逻辑
    OUT getResult(ACC accumulator);

    // 定义合并逻辑
    ACC merge(ACC a, ACC b);
}
```
使用 AggregateFunction 可以完成比较复杂的计算逻辑
```scala
class MyAverageAggregate extends AggregateFunction[(String, Long), (Long, Long), Double]{
  override def createAccumulator() = (0L, 0L)

  override def add(input: (String, Long), acc: (Long, Long)) = (acc._1 + input._2, acc._2 + 1L)

  override def getResule(acc: (Long, Long)) = acc._1 / acc._2

  override def merge(acc1: (Long, Long), acc2: (Long, Long)) = (acc1._1 + acc2._1, acc1._2 + acc2._2)
}

val inputStream: DataStream[(String, Long)] = ...
val aggregateWindowStream = inputStream.keyBy(_._1).window(SlidingEventTimeWindows.of(Time.hours(1), Time,minutes(10)))
    .aggregate(new MyAverageAggregate)
```
### ProcessWindowFunction
在一些特定的情况下增量聚合函数无法满足计算逻辑，此时需要窗口中的说有数据元素或需要操作窗口中的状态数据和窗口元数据，这是可以使用 ProcessWindowFunction 更加灵活地基于窗口全部数据元素的计算结果。

在实现 ProcessWindowFunction 接口的过程中，如果不需要操作状态数据，则只需要 process 方法既可以，该方法中定义了评估窗口和具体数据输出的逻辑。
```scala
val inputStream: DataStream[(String, Long)] = ...

val staticStream = inputStream.keyBy(_._1).window(SlidingEventTimeWindows.of(Time.hours(1), Time,minutes(10)))
    .process(new StaticProcessFunction)

class StaticProcessFunction extends ProcessWindowFunction[(String, Long, Int), (String, Long, Long, Long, Long, Long), String, TimeWindow]{
  override def process(key: String, ctx: Context, vals: Iterable[(String, Long, Int)], out: Collector[(String, Long, Long, Long, Long, Long)]): Unit = {
    // 求和
    val sum = vals.map(_._2).sum
    // 最小值
    val min = vals.map(_._2).min
    // 最大值
    val max = vals.map(_._2).max
    // 均值
    val avg = sum / vals.size
    val windowEnd = ctx.window.getEnd
  
    // 输出结果
    out.collect((key, min, max, sum, avg, windowEnd))
  }
}
```
将增量聚合函数和全窗口函数结合使用可以在提升计算性能的同时获取窗口的元数据信息，Kafka 提供了结合使用增量聚合函数和全窗口函数的 API
```scala
val inputStream: DataStream[(String, Long)] = ...

val result = inputStream。keyBy(_._1).timeWindow(Time.seconds(10))
    .reduce((r1: (String, Long, Int), r2: (String, Long, Int)) => {
      if (r1._2 > r2._2) r2 else r1    
}, (key: String, window: TimeWindow, minReadings: Iterable[(String, Long, Int)], out: Collector[(String, Long, Int)])=>{
    val min = minReadings.iterator.next()
    out.collect((window.getEnd, min))
})
```
ProcessWindowFunction 内部类 Context 保存了窗口中每个 Key 的状态数据，Context 中有两种类型的状态数据：
- globalState：每个 key 的全局状态数据
- windowState：每个窗口中每个 key 的状态数据，使用时需要及时通过调用 clear 方法清理状态数据

## 窗口触发器
数据接入窗口后需要满足窗口触发器的条件才能触发窗口函数的计算，Flink 内部提供了 EventTimeTrigger， ProcessTimeTrigger 和 CountTrigger 三种触发器对应不同的窗口。
- EventTimeTrigger：通过对比 Watermark 和窗口的 endTime 确定是否触发窗口计算，如果 Watermark 大于窗口的 endTime 则触发计算否则继续等待
- ProcessTimeTrigger：通过对比 ProcessTime 和窗口的 endTime 确定是否触发窗口计算，如果 processTime 大于窗口的 endTime 则触发计算否则继续等待
- ContinuousEventTimeTrigger：根据间隔时间周期性的触发窗口或者窗口的结束时间小于当前的 EventTime 则触发窗口计算
- ContinuousProcessingTimeTrigger：根据间隔是加你周期性的触发窗口或者窗口的结束时间小于当前 ProcessTime 则触发窗口计算
- CountTrigger：根据窗口中接入数据的数量是否达到阈值来计算是否触发窗口计算
- DeltaTrigger：根据窗口中接入的数据计算 Delta 指标是否超过指定的 Threshold 来计算是否触发窗口计算
- PurgingTrigger：可以将任意触发器作为参数转换为 Purge 类型触发器，计算完成后数据将被清理

通过继承抽象类 `Trigger` 并复写提供的方法即可自定义窗口触发器
```java
public abstract class Trigger<T, W extends Window>{

    // 针对每一个接入窗口的数据元素进行触发操作
    public abstract TriggerResult onElement(T element, long timestamp, W window, TriggerContext ctx) throws Exception;

    // 根据接入窗口的 EventTime 进行触发操作
    public abstract TriggerResult onProcessingTime(long time, W window, TriggerContext ctx) throws Exception;

    // 根据接入窗口的 ProceeTime 进行触发操作
    public abstract TriggerResult onEventTime(long time, W window, TriggerContext ctx) throws Exception;

    // 对多个窗口进行 Merge 操作同时合并状态
    public void onMerge(W window, OnMergeContext ctx) throws Exception;

    // 执行窗口及状态数据的清除
    public abstract void clear(W window, TriggerContext ctx) throws Exception;
}
```
窗口触发器执行触发操作的返回结果有四种类型：
- CONTINUE：当前不触发计算，继续等待
- FIRE：触发计算，但是数据继续保留
- PURGE：清除窗口内的数据，但是不触发计算
- FIRE_AND_PURGE：触发计算并清除窗口内的数据

窗口触发器需要通过 trigger 方法设置
```scala
val windowStream = stream.keyBy(_._1).window(EventTimeSessionWindows.withGap(Time.seconds(10)))
    .trigger(ContinuousEventTimeTrigger.of(Time.seconds(10)))
```
当使用自定义触发器时，默认的触发器会被覆盖

## 剔除器
Evictors 时 Flink 窗口机制中的一个可选组件，主要作用是对进入 WindowFunction 中的数据进行剔除处理。Flink 提供了 CountEvictor，DeltaEvictor, TimeEvictor 三种剔除器，

Evictor 通过调用 WindowedStream 的 evictor 方法使用，默认 Evictor 对数据的剔除是在窗口函数处理之前。

- CountEvictor：保持窗口中具有固定数量的数据，超过指定大小的数据将会被剔除
- DeltaEvictor：通过定义的 DeltaFunction 以及 threshold 计算数据与最新元素之间的 delta，超过阈值的数据将会被剔除
- TimeEvictor：通过指定时间间隔，将数据时间早于最新数据的时间与保留时间差的剔除

通过实现 Evictor 接口可以自定义剔除器，Evictor 定义两个需要复写的方法：
```java
public interface Evictor<T, W extends Window>{

    // 窗口函数触发之前的数据剔除逻辑
    void evictBefore(Iterable<TimestampedValue<T>> elements, int size, W window, EvictorContext evictorContext);

    // 窗口函数触发之后的数据剔除逻辑
    void evictAfter(Iterable<TimestampedValue<T>> elements, int size, W window, EvictorContext evictorContext);
}
```

## 延迟数据处理
基于 EventTime 的窗口处理流式数据虽然提供了 Watermark 机制，但只能在一定程度上解决数据乱序问题。

在某些数据延迟特别厉害的情况下，Watermark 也无法等到全部数据接入窗口再进行处理，Flink 默认会将这些延迟的数据进行丢弃处理，如果希望对这些延迟的数据进行处理，则需要使用 AllowedLateness 机制对迟到的数据进行额外的处理。

WindowedStream 提供了 allowLateness 方法来指定是否对迟到的数据进行处理，方法需要传入参数表示允许延迟的最大时间，Flink 在窗口计算过程中会将窗口的结束时间加上延迟，作为窗口最后的释放时间。

当接入数据的 EventTime 未超过窗口的释放时间，但 Watermark 已经超过窗口的结束时间则触发窗口计算，如果事件事件超过了窗口的释放时间则只能丢弃处理。

默认情况下 GlobalWindow 的 Lateness 时间未 Long.MAX_VALUE，数据会积累在窗口中等待触发计算，其他窗口默认的 Lateness 为 0 表示不允许有延时数据的情况

对于延迟的数据通常不会混入正常的计算流程中，而是通过侧输出流的方式存储起来然后用于分析
```scala
// 定义延时数据标记
val lateOutputTag = OutputTag[T]("late-data")
val input: DataStream[T] = ...
val result = input.keyBy(_._1)
    .timeWindow(Time.seconds(10))
    .allowedLateness(Time.seconds(1))
    // 对迟到的数据进行标记
    .sideOutputLateData(lateOutputTag)
// 从窗口中获取迟到数据的统计结果
val lateStream = resutl.getSideOutput(lateOutputTag)
```

## 连续窗口计算
流式数据在窗口中完成计算之后会转换成 DataStream，后续的计算可以按照 DataStream 进行

对同一个 DataStream 进行不同的窗口处理后输出的结果在不同的 DataStream 中，Flink 会在不同的 Task 中执行窗口操作，相互之间的元数据并不会共享。

连续窗口计算表示上游窗口的计算结果是下游窗口计算的输入，窗口算子和算子之间上下游关联，窗口之间的元数据可以共享。

Flink 支持窗口上的多流合并，即在一个窗口中按照相同条件对两个输入流数据进行关联操作，需要保证输入的 Stream 要构建在相同的窗口上，并使用相同类型的 Key 进行关联。
```scala
inputStream1: DataStream[(Long, String, Int)] = ...
inputStream2: DataStream[(Long, String, Int)] = ...

inputStream1.join(inputStream2)
    .where(_._1)  // 指定 inputStream1 关联的 key
    .equalTo(_._2)  // 指定 inputStream2 关联的 key
    .timeWindow(Time.seconds(10))
    .apply(<JoninFunction>)   // 指定窗口计算函数
```
窗口 Join 的过程中数据的合并采用 inner-join 的方式合并，也就是说在相同的窗口中只有具有相同 key 的数据才会参与计算。

Flink 提供了滚动窗口关联、滑动窗口关联、会话窗口关联以及间隔关联四种流合并的操作

### 滚动窗口关联
滚动窗口关联是将滚动窗口中相同 key 的两个 DataStream 数据集中的元素进行关联，并通过 JoinFunction 计算结果
```scala
val blackStream: DataStream[(Int, Long)] = ...
val whiteStream: DataStream[(Int, Long)] = ...

val windowStream: DataStream[(Int, Long)] = blackStream.join(whiteStream)
    .where(_._1).equalTo(_._1)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .apply((black, white) => (bloack,_1, black._2 + white._2))
```
### 滑动窗口关联
滑动窗口关联时会出现重叠的情况，连个 DataStream 在窗口中根据相同的 Key 进行内连接。
```scala
val blackStream: DataStream[(Int, Long)] = ...
val whiteStream: DataStream[(Int, Long)] = ...

val windowStream: DataStream[(Int, Long)] = blackStream.join(whiteStream)
    .where(_._1).equalTo(_._1))
    .window(SlidingEventTimeWindows.of(Time.hours(1), Time.seconds(10)))
    .apply((black.white) => (black._1, black._2 + white._2))
```
### 会话窗口关联
会话窗口关联是对两个 Stream 的数据元素进行窗口关联操作，窗口中含有两个数据集元素，并且元素具有相同的 key 则输出关联计算结果。

### 间隔关联
间隔关联的数据元素关联范围不依赖窗口划分，而是通过 DataStream 元素的时间加上或减去指定的 interval 作为关联窗口，然后和另外一个 DataStream 的数据在窗口内的聚合。