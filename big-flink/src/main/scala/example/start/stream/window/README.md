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
Flink 支持两种 Window，