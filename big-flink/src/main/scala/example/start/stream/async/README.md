## 异步 I/O 操作

Flink 在与外部系统进行数据交互时可以通过 RichMapFunction 来创建外部数据系统的连接客户端，然后通过客户端将数据写入外部系统或者从外部系统读取数据。

考虑到网络因素以及外部系统的性能，使用同步的方式会减低整个 Flink 系统的效率，Flink 引入了异步 I/O 支持以异步的方式和外部系统进行数据交互从而提升系统的吞吐量。

```scala
class AsyncDBFunction extends AsyncFunction[String, (String, String)]{

  // 创建异步客户端
  lazy val dbClient: DBClient = new DBClient(host, port)
  // 用于回调
  implicit lazy val executor: ExecutionContext = ExecutionContext.fromExecutor(Executors.directExecutor())


  override def asyncInvoke(input: String, resultFuture: ResultFuture[(String, String)]): Unit = {
    // 异步查询
    val resultFutureRequested: Future[String] = dbClient.query(input)

    resultFutureRequested.onSuccess{
      case result: String => resultFuture.complete(Iterable(input, result))
    }
  }
}

object AsyncDBFunction{

  val stream: DataStream[String] = ...

  val resultStream: DataStream[(String, String)] = AsyncDataStream.unorderedWait(stream,
    new AsyncDatabaseRequest(), 1000, TimeUnit.SECONDS, 100)


}
```
异步 IO 提供了 capacity 和 timeout 两个参数来配置异步操作，其中 timeout 定义异步超时时间，超时则会认为请求超时，capacity 表示异步请求的并发数，操作则会触发反压机制抑制数据的接入。

使用异步 IO 会导致数据接入或者输出的顺序发生变化，Flink 针对这种情况提供两种模式的异步 IO
- 乱序模式：调用 AsyncDataStream.unorderedWait() 方法
- 顺序模式：调用 AsyncDataStream.orderedWait() 方法，顺序模式需要将所有的数据缓存起来然后按照输入的顺序处理

如果使用 EventTime 时间语义处理数据流，异步 IO 保证 Watermark 的顺序，但是不能保证数据保持原来的顺序。