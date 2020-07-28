### 时间轮
Kafka 中存在大量的延时操作，Kafka 基于时间轮的概念自定义实现了一个用于延时功能的定时器 SystemTimer 使得插入和删除时间复杂度为O(1)。

Kafka 中的时间轮(TimingWheel)是一个存储定时任务的环形队列，底层采用数组实现，数组中的每个元素可以存放一个定时任务列表(TimeTaskList)，任务列表是一个环形的双向链表，链表中的每一项表示的是定时任务项(TimeTaskEntry)，其中封装了真正的定时任务(TimeTask)。

时间轮由多个时间格组成，每个时间格代表当前时间轮的基本时间跨度(tickMs)，每个时间轮的时间格数是固定的表示为 wheelSize，整个时间轮的时间跨度(interval)为 tickMs * wheelSize。时间轮有一个表盘指针(currentTime)表示时间轮当前所处的时间，currentTime 可以将整个时间轮划分为到期部分和未到期部分，currentTime 当前指向的时间格表示刚好到期，需要处理此时间格所对应 TimerTaskList 中所有的任务。
```scala
private[timer] class TimingWheel(tickMs: Long, wheelSize: Int, startMs: Long, taskCounter: AtomicInteger, queue: DelayQueue[TimerTaskList]) {

  private[this] val interval = tickMs * wheelSize
  private[this] val buckets = Array.tabulate[TimerTaskList](wheelSize) { _ => new TimerTaskList(taskCounter) }

  private[this] var currentTime = startMs - (startMs % tickMs) // rounding down to multiple of tickMs

  // overflowWheel can potentially be updated and read by two concurrent threads through add().
  // Therefore, it needs to be volatile due to the issue of Double-Checked Locking pattern with JVM
  @volatile private[this] var overflowWheel: TimingWheel = null

  // ...
}
```
- TimingWheel 在创建的时候以当前系统时间为第一层时间轮的起始时间(startMs)
- TimingWheel 中的每个双向环形链表 TimerTaskList 都会有一个哨兵节点(sentinel)，引入哨兵节点可以简化边界条件
- 除了第一层时间轮，其余高层时间轮的起始时间(startMs)都设置为创建此层时间轮前面第一轮 currentTime。每一层的 currentTime 都必须是 tickMs 的整数倍，如果不满足则会将 currentTime 修剪为 tickMs 的整数倍，以此与时间轮中的时间格的到期时间规范对应起来，修剪的方式为 currentTime = startMs - (startMs % tickMs)。
- Kafka 中的定时器之需持有 TimingWheel 的第一层时间轮的引用，并不会直接持有其他高层的时间轮，但每一层时间轮都会有一个引用(overflowWheel)指向更高一层的引用，以此层级调用可以实现定时器间接持有各个层级时间轮的引用

Kafka 中的定时器使用了 JDK 中的 DelayQueue 来协助推进时间轮。具体的做法是对于每个使用到的 TimerTaskList 都加入 DelayQueue，然后使用一个名为 ExpiredOperationReaper 的线程获取 DelayQueue 中到期的任务列表，当获取到 TimerTaskList 之后可以根据对应的 expiration 来推进时间轮也可以执行 TimerTaskList 相应的操作。

Kafka 中的 TimingWheel 专门负责执行插入和删除 TimerTaskEntry 的操作，而 DelayQueue 专门负责时间推进的任务。

### 延时操作
如果生产者发送消息时的 ack 参数设置为 all 时，需要 ISR 中所有的副本都确认收到消息之后才能收到响应结果，否则获取超时异常，生产者请求超时由参数 ```request.timeout.ms``` 配置，默认 30000。

在将消息写入 leader 副本的日志文件之后，Kafka 会创建一个延时的生产操作(DelayedProduce)用来处理消息正常写入所有副本或超时的情况并返回相应的结果给客户端。

Kafka 中多种延时操作，延时操作需要延时返回响应结果，因此需要一个超时时间(delayMs)，如果在超时时间内没有完成任务那么需要强制完成以返回响应结果给客户端；延时操作不同于定时操作，可以在所设定的超时时间之前完成，所以延时操作能够支持外部事件的触发。

延时操作创建之后会被加入延时操作管理器(DelayedOperationPurgatory)来做专门的处理，延时操作有可能会超时，每个延时操作管理器都会配备一个定时器(SystemTimer)来做超时管理，定时器的底层采用时间轮实现。延时操作需要支持外部事件的触发，因此配备一个监听池来负责监听每个分区的外部事件。

#### 延时生产
对于生产延时(DelayedProduce) 而言它的外部事件是写入消息的某个分区的 HW 发生增长，也就是说随着 follower 副本不断地与 leader 副本进行同步进而促使 HW 进一步增长，HW 每增长一次都会检测是否能够完成此次延时生产操作，如果可以就执行以此返回响应结果给客户端，如果在超时时间内始终无法完成，则强制执行。如果客户端设置的 acks 参数不为 all，或者没有成功的消息写入，那么就直接返回结果给客户端，否则就需要创建延时生产操作并存入延时操作管理器，最终要么由外部事件触发，要么由超时触发而执行。
#### 延时拉取
Kafka 在处理拉取请求时，会先读取一次日志文件，如果收集不到足够多(fetchMinBytes，由参数 fetch.min.bytes 设置，默认为 1)的消息，那么就会创建一个延时拉取操作(DelayedFetch)以等待拉取到足够数量的消息。当延时拉取操作执行时会再读取一次日志文件，然后将拉取的结果返回给 follower 副本。延时拉取操作也会有一个专门的延时操作管理器负责管理，如果拉取进度一直没有追赶上 leader 副本，那么在拉取 leader 副本的消息时一般拉取的消息大小都会不小于 fetchMinBytes。延时拉取操作同样是由超时触发或外部事件触发而被执行的，超时触发是等到超时时间之后触发第二次读取日志文件的操作。




https://my.oschina.net/anur/blog/2252539