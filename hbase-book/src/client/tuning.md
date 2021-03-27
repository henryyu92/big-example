## 客户端调优

由于 HBase 客户端比较重量级，需要处理比较复杂的操作，这种复杂性有时会使得客户端出现一些异常。


- Region 的大小控制在 10-50 GB
- Cell 的大小不要超过 10M，如果有比较大的数据块建议将数据存放在 HDFS 而将引用存放在 HBase
- 通常每个表有 1 到 3 个列族
- 一个表有 1-2 个列族和 50-100 个 Region 是比较好的
- 列族的名字越短越好，因为列族的名字会和每个值存放在一起



HBase 客户端到服务端通信过程中会由于多种原因需要重试，在发起 RPC 请求时有一些常见的超时参数设置：

- ```hbase.rpc.timeout```：单次 RPC 请求的超时时间，默认 60000 ms，超时后抛出 TimeoutException
- ```hbase.client.tries.number```：客户端单次 API 调用时最多容许发生 RPC 重试的次数，默认 35 次
- ```hbase.client.pause```：连续两次 RPC 重试之间的休眠，默认 100 ms，HBase 的休眠时间是按照随机退避算法计算的，因此休眠时间随着重试次数增加而增加
- ```hbase.client.operation.timeout```：客户端单次 API 调用的超时时间，默认值是 120000 ms，此时间包含 RPC 超时时间以及重试休眠时间

HBase 客户端提供了 CAS 接口，保证在高并发场景下读取与写入的原子性。这些 CAS 接口在 RegionServer 上是 Region 级别的，即在单个 Region 上是串行的，而在多个 Region 之间是并行的。