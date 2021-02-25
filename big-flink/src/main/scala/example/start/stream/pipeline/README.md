## 作业链

Flink 作业中可以指定链条将相关性非常强的转换操作绑定在一起，这样能够让转换过程上下游的 Task 在同一个 Pipeline 中执行，进而避免因为数据在网络或者线程间传输导致的开销。

Flink 的操作默认开启了 TaskChain 以提高整体的性能，Flink 提供了细粒度的作业链控制，使得用户根据自己的需要创建作业链或者禁止作业链。

Flink 提供了全局禁止作业链操作来关闭整个 Flink 作业的链条
```scala
StreamExecutionEnvironment.disableOperatorChaining()
```
关闭全局作业链后，如果需要创建局部作业链，则需要通过 startNewChain 方法来创建，且只能对当前方法相邻的操作符建立作业链
```scala
// 两个 map 之间进行链表绑定，而其他算子不会绑定
stream.filter().map().startNewChain().map()
```
Flink 还支持关闭局部的链条绑定，通过 disableChaining 方法可以关闭前面的操作上的链条
```scala
// 只禁用 map 上的链条，不会对其他操作符产生影响
stream.map.disableChaining()
```

### Slot 资源组

Slot 是 Flink 系统中最小的资源单元，Flink 在 TaskManager 启动后会管理当前的所有 Slot，当作业提交到 Flink 集群后 JobManager 统一分配 Slot 数量。

Slot 资源共享可以将多个 Task 计算过程在同一个 Slot 中完成，这样能够对特定的 Task 进行物理隔离。

如果当前操作符中所有的 input 操作均具有相同的 slot group 则该操作符会继承前面操作符的 slot group，然后在同一个 Slot 中处理数据，如果不是则当前的操作符会默认选择 default 的 slot group，然后将数据发送到对应的 Slot 中进行处理。

如果没有显式指定 slot group 则所有的操作符都使用默认的 slot group，因此 Task 上的数据转换可能会在不同的 Slot 中完成

Flink 提供了指定操作符执行的 slot group
```scala
// filter 操作将会在同一个 Slot 上执行
stream.filter().slotSharingGroup("")
```