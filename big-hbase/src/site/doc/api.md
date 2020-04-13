## 客户端

HBase 本身是 Java 开发的，因此非 Java 语言的客户端需要先访问 ThriftServer，然后通过 ThriftServer 的 Java HBase 客户端来请求 HBase 集群。

HBase 也支持 Shell 交互式客户端，其本质是用 JRuby 脚本调用 HBase Java 客户端来实现。

HBase 将 Region 定位功能设计在客户端上，因此 HBase 的客户端并不轻量级。


### 命令行

### Java API

#### SCAN

HBase 客户端的 Scan 操作能够设置多个维度的属性，使得 Scan 操作比较复杂。Scan 操作在获取到 scanner 之后调用 next 方法获取数据时先到 cache 队列中拿数据，如果 cache 队列中数据不足则会发起一次 RPC 向服务端请求数据，客户端收到数据之后通过 scanResultCache 把这些数据内的多个 cell 进行重组，最终组成用户需要的结果放入 cache 中。整个 RPC 请求到重组数据放入 cache 的过程称为 loadCache 操作。

Scan 的重要设置：
- ```caching```：放入到 cache 中的 rpc 请求结果数量
- ```batch```：结果中的 cell 个数
- ```allowPartial```：容忍部分数据
- ```maxResultSize```：loadCache 时单词 RPC 操作最多拿到的结果的字节数

HBase 客户端到服务端通信过程中会由于多种原因需要重试，在发起 RPC 请求时有一些常见的超时参数设置：
- ```hbase.rpc.timeout```：单次 RPC 请求的超时时间，默认 60000 ms，超时后抛出 TimeoutException
- ```hbase.client.tries.number```：客户端单次 API 调用时最多容许发生 RPC 重试的次数，默认 35 次
- ```hbase.client.pause```：连续两次 RPC 重试之间的休眠，默认 100 ms，HBase 的休眠时间是按照随机退避算法计算的，因此休眠时间随着重试次数增加而增加
- ```hbase.client.operation.timeout```：客户端单次 API 调用的超时时间，默认值是 120000 ms，此时间包含 RPC 超时时间以及重试休眠时间

HBase 客户端提供了 CAS 接口，保证在高并发场景下读取与写入的原子性。这些 CAS 接口在 RegionServer 上是 Region 级别的，即多个 Region 之间是可以并行执行

#### Filter

HBase 在 Scan 的时候可以设置多个 Filter，使得大量无效数据可以在服务端内部过滤，相比直接返回全表数据到客户端然后在客户端过滤要高效的多。HBase 提供了多种 Filter，在使用 Filter 的过程中也需要注意：
- ```PrefixFilter```：过滤 rowkey 为指定前缀的数据，但是即使指定了前缀，Scan 也会从最开始的 rowkey 开始扫描从而会扫描大量的无效行，建议在使用 PrefixFilter 时指定 startRow 参数尽量过滤掉无用的数据扫描，或者将 PrefixFilter 转换成等价的 Scan
- ```PageFilter```：用于分页的 Filter，但是由于 HBase 中的 Filter 状态全部都是 Region 内有效的，Region 切换时其内部计数器会被清 0，因此可能导致扫描的数据跨 Region 导致返回数据量超过设定的页数量。使用 Scan 的 setLimit 方法可以实现分页功能
- ```SingleColumnValueFilter```：用于根据列过滤数据，SingleColumnValue 必须遍历一行数据中的每一个 cell，因而不能和其他 Filter 组合成 FilterList

#### PUT
