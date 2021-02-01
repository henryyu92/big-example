## 窗口计算

Flink DataStream 将窗口抽象成独立的 Operator，每个窗口算子中包含 `WindowsAssigner`、`WindowsTrigger`、`Evictor`、`Lateness`、`OutputTag` 以及 `WindowFunction` 等组件，其中 `WindwosAssigner` 和 `WindowsFunction` 必须指定，其他组件按需指定。

- `WindowsAssigner`：指定窗口的类型，定义如何将数据流分配到不同的窗口
- `WindowsTrigger`：指定窗口触发计算的条件
- `Evictor`：指定从窗口中剔除数据的条件
- `Lateness`：标记是否处理迟到数据，以及迟到数据到达时是否触发计算
- `OutputTag`：标记标签，然后通过 OutSideOutput 将窗口中的数据根据标签输出

### `WindowsAssigner`
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
var stream: DataStream[T];

val eventTimeTumblingWindowStream = stream.keyBy(_.1)
//  .window(TumblingProcessTimeWindows.of(Time.second(10)))
    .window(TumblingEventTimeWindows.of(Time.second(10)))
```
默认窗口时间的时区是 UTC-0 因此在其他时区需要考虑时差

滑动窗口
