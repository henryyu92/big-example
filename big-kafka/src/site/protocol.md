### 协议设计
Kafka 自定义了一组基于 TCP 的二进制协议，只要遵守这组协议的格式，就可以向 Kafka 发送消息或者从 Kafka 中拉取消息等操作。

Kafka 中每种协议类型都有对应的请求(Request)和响应(Response)，他们都遵循特定的协议模式。每种类型的 Request 都包含相同结构的协议请求头(RequestHeader)和不同结构的协议请求体(RequestBody)。
协议请求头中包含 4 个域(Field)：
- api_key：API 标识，比如 PRODUCER、FETCH 分别表示发送消息和拉取消息的请求
- api_version：API 版本号
- coorelation_id：由客户端指定的一个数字来唯一地标识这次请求的 id，服务端在处理完请求之后也会把同样的 coorelation_id 写到 Response 中，这样客户端就能把请求和响应对应起来
- client_id：客户端 id

每种类型的 Response 也包含相同结构的协议响应头(ResponseHeader)和不同结构的协议响应体(ResponseBody)，协议响应头中只有 correlation_id 一个域

#### 消息发送协议(ProduceRequest/ProduceResponse)
消息发送协议的 aip_key 为 0，表示 PRODUCE。

ProduceRequest 的请求体包含了多个域：
- transactionalId：事务 id，如果不支持事务则为 null
- timeout：请求超时时间，对应客户端参数 ```request.timeout.ms```，默认 30000
- acks：重试次数，对应客户端参数 ```acks```
- partitionRecords：ProduceRequest 中发送的消息

消息累加器 RecordAccumulator 中的消息是以 <TopicPartition, Deque<ProducerBatch>> 的形式进行缓存的，之后由 Sender 线程转变为 <Node, List<ProducerBatch>> 的形式，针对每个 Node，Sender 线程在发送消息前会将对应的 List<ProducerBatch> 形式的内荣转变为 ProduceRequest 的具体结构。

生产者客户端在发送 ProduceRequest 请求之后需要等待服务器的响应 ProduceResponse：
- throttleTimeMs
- responses：响应的数据

响应是针对分区粒度进行分化的，这样 ProduceRequest 和 ProduceResponse 做到了一一对应。

#### 消息拉取协议(FetchRequest/FetchResponse)
拉取消息的协议的 api_key 为 1 表示 FETCH。

FetchRequest 请求体包含了多个域：
- replicaId
- maxWait
- minBytes
- maxBytes
- isolationLevel
- fetchData
- toForget
- metadata

不管是 follower 副本还是普通的消费者客户端，如果要拉取某个分区中的消息，就需要指定详细的拉取信息，也就是需要设定 partition、fetchOffset、logStartOffset 和 maxBytes 这 4 个域的具体值。

FetchRequest 引入了 sessionId、epoch 和 toForget 等域，sessionId 和 epoch 确定一条拉取连读的 fetch session，当 session 建立或变更时会发送全量式的 FetchRequest，所谓全量式就是指请求体中包含所有需要拉取的分区信息；当 session 稳定时则会发送增量式的 FetchRequest 请求，里面的 topics 为空因为 topics 域的内容已经缓存在了 session 链路的两侧。如果需要从当前 fetch session 中取消对某些分区的拉取订阅，则可以使用 toForget 域实现。

与 FetchRequest 对应的 FetchResponse 的结构：
- throttleTimeMs
- error
- sessionId
- responseData
