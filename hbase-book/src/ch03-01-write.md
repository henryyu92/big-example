# 写数据

## HBase 写流程

HBase 采用 LSM 树架构，适用于写多读少的应用场景。HBase 没有提供 update, delete 接口，HBase 中对数据的更新、删除也被认为是一个写入操作，更新操作会写入一个最新版本的数据，删除操作会写入一条标记为 delted 的数据。

HBase 的写入流程分为三个阶段：
- 客户端处理阶段：客户端将写请求预处理，根据集群元数据定位写入数据所在的 RegionServer，并将写请求发送给对应的 RegionServer
- RegionServer 写入阶段：RegionServer 接收到写入请求之后将数据解析写入 WAL 和对应 Region 列簇的 MemStore，并返回成功
- MemStore Flush 阶段：当 Region 中 MemStore 容量超过一定阈值，系统会异步执行 flush 操作将内存中的数据写入文件形成 HFile

HBase 客户端处理写入请求核心流程分为三步：
- 用户提交 put 请求后，HBase 客户端会将写入的数据添加到本地缓冲区中，当本地缓冲区大小超过阈值(默认设置为 2M)后会通过 AsyncProcess 异步批量提交。当 HBase 配置 autoflush = true 时会直接将 put 请求发送给 RegionServer 处理，默认为 true
- 在提交请求之前，HBase 会在元数据表 hbase:meta 中根据 rowkey 找到数据归属的 RegionServer，定位通常是通过 HConnection.locateRegion 方法完成。如果是批量请求，会将 rowkey 按照 RegionLocation 分组，不同的分组发送到不同的 RegionServer，每个分组对应一次 RPC 请求
- HBase 为每个 HRegionLocation 构造一个远程 RPC 请求 MultiServerCallable 并通过 rpcCallerFactory.newCaller 执行调用，将请求经过 Protobuf 序列化后发送给对应的 RegionServer

HBase client 和 ZooKeeper 以及 RegionServer 交互：
- 客户端根据写入的表以及 rowkey 在元数据缓存中查找，如果能查找到该 rowkey 所在的 RegionServer 以及 Region，则直接发送请求到目标 RegionServer
- 如果客户端缓存没有查找到对应的 rowkey 信息，则需要到 ZooKeeper 的 /hbase-root/meta-region-server 节点查找 HBase 元数据表所在的 RegionServer，然后向 hbase:meta 所在的 RegionServer 发送查询请求，在元数据表中查找 rowkey 所在的 RegionServer 以及 Region 信息。客户端收到返回的结果后缓存到本地，以备下次使用
- 客户端根据 rowkey 相关元数据信息将写入请求发送给目标 RegionServer，RegionServer 接收到请求之后会解析出具体的 Region 信息，查到对应的 Region 对象，并将数据写入目标 Region 的 MemStore 中

RegionServer 接收到写入请求之后，首先反序列化为 put 对象，然后执行各种检查操作，比如 Region 是否只读、MemStore 大小是否超过 blockingMemstoreSize 等。检查完成之后会执行一些核心操作：
- Acquire locks：HBase 中使用行锁保证对同一行数据的更新都是互斥操作，用以保存更新的原子性
- Update LATEST_TIME STAMP：更新所有待写入 KeyValue 的时间戳为当前系统时间
- Build WAL edit：HBase 使用 WAL 机制保证数据可靠性，写入操作中所有 KeyValue 会构建成一条 WALEdit 记录
- Append WALEdit To WAL：WALEdit 记录顺序写入 HLog 中，此时不需要执行 sync 操作，内部使用 disruptor 实现高效的生产者消费者队列来实现 WAL 的追加写入操作
- Write back to MemStore：写入 WAL 之后再将数据写入 MemStore
- Relase row locks：释放行锁
- Sync wal：HLog 真正 sync 到 HDFS，在释放行锁之后执行 sync 操作是为了尽量减少持锁时间，提升写性能。如果 sync 失败，执行回滚操作将 MemStore 中已经写入的数据移除
- 结束写事务：此时该线程的更新操作才会对其他读请求可见，更新才实际生效

随着数据的不断写入，MemStore 中存储的数据会越来越多，系统为了将使用的内存保持在一个合理的水平，会将 MemStore 中的数据写入文件形成 HFile。flush 阶段是 HBase 非常核心的阶段，需要重点关注三个问题：
- MemStore Flush 的触发时机
- MemStore flush 的整体流程
- HFile 的构建过程

HBase 中 HLog 保证成功写入 MemStore 中的数据不会因为进程异常退出或者机器宕机而丢失，HBase 定义了多个 HLog 持久化等级，使得可以在数据高可靠和写入性能之间进行权衡

HBase 可以通过设置 HLog 的持久化等级决定是否开启 HLog 机制以及 HLog 的落盘方式，HLog 的持久化等级分为五个等级：
- SKIP_WAL：只写缓存，不写 HLog 日志，这种方式可以极大地提升写入性能单数句有丢失的风险
- ASYNC_WAL：异步将数据写入 HLog 日志中
- SYNC_WAL：同步将数据写入日志文件中，并没有真正落盘
- FSYNC_WAL：同步将数据写入日志文件并强制落盘，可以保证数据不丢失，但是性能相对比较差
- USER_DEFAULT：默认 HBase 使用 SYNC_WAL 等级持久化

可以通过客户端设置 HLog 持久化等级：
```java
put.setDurability(Durability.SYNC_WAL);
```

HBase 使用 LMAX Disruptor 框架实现了无锁有界队列操作。当调用 append 后 WALEdit 和 LogKey 会被封装成 FSWALEntry 类，进而封装成 RingBufferTruck 类放入同一个队列中，然后工作线程会被阻塞，等待 notify 来唤醒。Disruptor 框架中有且仅有一个消费者线程工作，从队列中一次取出 RingBufferTruck 对象，然后根据选项操作：
- 如果 RingBufferTruck 对象中封装的是 FSWALEntry，就会执行文件 append 操作，将记录追加写入 HDFS 文件中，此时数据可能并没有实际酷哦盘，而只是写入到文件缓存
- 如果 RingBufferTruck 对象是 SyncFuture，会调用线程池的线程异步地批量刷盘，刷盘成功之后唤醒工作线程完成 HLog 的 sync 操作

KeyValue 写入 Region 分为两步：追加写入 HLog，然后写入 MemStore。MemStore 使用 ConcurrentSkipListMap 来实际存储 KeyValue，能够后支持大规模并发写入，且本身是有序存储的，有利于数据落盘和提升 MemStore 中的 KeyVlaue 查找性能。KeyValue 写入 MemStore 并不会每次都随机在堆上创建一个内存对象，然后再放到 ConcurrentSkipListMap 中，这会带来非常严重的内存碎片，进而可能频繁触发 Full GC。HBase 使用 MemStore-Local Allocation Buffer(MSLAB)机制预先申请一个大的(2M) Chunk 内存，写入的 KeyValue 会进行一次封装，顺序拷贝这个 Chunk 中，这样 MemStore 中的数据从内存 flush 到硬盘的时候，JVM 内存留下来的就不再是小的无法使用的内存碎片，而是大的可用的内存碎片。MemStore 的写入流程可以分为 3 步：
- 检查当前可用的 Chunk 是否写满，如果写满则重新申请一个 2M 的 Chunk
- 将当前 KeyValue 在内存中重新构建，在可用 Chunk 的指定 offset 处申请内存创建一个新的 KeyValue 对象
- 将新创建的 KeyValue 对象写入 ConcurrentSkipListMap 中

HBase 会在以下集中情况触发 flush 操作：
- MemStore 级别限制：当 Region 中任意一个 MemStore 的大小达到了上限(hbase.hregion.memstore.flush.size，默认 128M)，会触发 MemStore 刷新
- Region 级别限制：当 Region 中所有 MemStore 的大小总和达到上限(hbase.hregion.memstore.block.multilier * hbase.hregion.memstore.flush.size)，会触发 MemStore 刷新
- RegionServer 级别限制：当 RegionServer 中 MemStore 的大小总和超过低水位阈值 hbase.regionserver.global.memstore.size.lower.limit * hbase.regionserver.global.memstore.size，RegionServer 开始强制执行 flush，先 flush MemStore 最大的 Region，在 flush 次大的，依次执行。如果此时写入吞吐量依然很高，导致总 MemStore 大小超过高水位阈值 hbase.regionserver.global.memstore.size，RegionServer 会阻塞更新并强制执行 flush，直到总 MemStore 大小下降到低水位阈值
- 当一个 RegionServer 中 HLog 数量达到上限(hbase.regionserver.maxlogs)时，系统会选取最早的 HLog 对应的一个或多个 Region 进行 flush
- HBase 定期刷新 MemStore：默认周期为 1 小时，以确保 MemStore 不会长时间没有持久化。为避免所有的 MemStore 在同一时间都进行 flush 操作而导致的问题，定期 flush 操作有一定时间的随机延迟
- 手动执行 flush：通过 shell 命令 flush 'tablename' 或者 flush 'regionname' 分别对一个表或者一个 Region 进行 flush

HBase 采用了类似于二阶段提交的方式将整个 flush 过程分为了三个阶段：
- prepare 阶段：遍历当前 Region 中所有 MemStore，将 MemStore 中当前数据集 CellSkipListSet(采用 ConcurrentSkipListMap) 做一个快照 snapshot，然后再新建一个 CellSkipListMap 接收新的数据写入。prepare 阶段需要添加 updateLock 对写请求阻塞，结束之后会释放该锁，持锁时间很短
- flush 阶段：遍历所有 MemStore，将 prepare 阶段生成的 snapshot 持久化为临时文件，放入目录 .tmp 下，这个过程涉及到磁盘 IO，因此比较耗时
- commit 阶段：遍历所有的 MemStore，将 flush 阶段生成的临时文件移到指定的 ColumFamily 目录下，针对 HFile 生成对应的 storefile 和 Reader，把 storefile 添加到 Store 的 storefile 列表中，最后再清空 prepare 阶段生成的 snapshot

HBase 执行 flush 操作后将内存中的数据按照特定格式写入 HFile 文件。MemStore 中 KV 在 flush 成 HFile 时首先构建 Scanned Block 部分，即 KV 写入之后首先构建 Data Block 并依次写入文件，形成 Data Block 的过程中也会依次构建形成 Leaf index Block、Bloom Block 并依次写入文件。一旦 MemStore 中所有 KV 都写入完成，Scanned Block 部分就构建完成。

#### 构建 Scanned Block
MemStore 中 KV 数据写入 HFile 分为 4 个步骤：
- MemStore 执行 flush，首先新建一个 Scanner，这个 Scanner 从存储 KV 数据的 CellSkipListSet 中依次从小到大读出每个 cell。读取的顺序性保证了 HFile 文件中数据存储的顺序性，同时读取的顺序性是保证 HFile 索引构建以及布隆过滤器 Meta Block 构建的前提
- appendGeneralBloomFilter：在内存中使用布隆过滤器算法构建 BloomBlock
- appendDeleteFamilyBloomFilter：针对标记为 DeleteFamily 或者 DeleteFamilyVersion 的 cell，在内存中使用布隆过滤器算法构建 Bloom Block
-  HFile.Writer.append：将 cell 写入 Data Block 中

#### 构建 Bloom Block
Bloom Block 是布隆过滤器的存储，在内存中维护了多个名为 chunk 的数据结构，一个 chunk 主要由两个元素组成：
- 一块连续的内存区域，主要存储一个特定长度的数组。默认数组中所有位都是 0，对于 row 类型的布隆过滤器，cell 进来之后会对其 rowkey 执行 hash 映射，将其映射到位数组的某一位，该位的值修改为 1
- firstkey 是第一个写入该 chunk 的 cell 的 rowkey，用来构建 Bloom Index Block

cell 写进来之后，首先判断当前 chunk 是否已经写满，写满的标准是这个 chunk 容纳的 cell 个数是否超过阈值，如果超过阈值，就会重新申请一个新的 chunk，并将当前 chunk 放入 ready chunks 集合中，如果没有写满则根据布隆过滤器算法使用多个 hash 函数分别对 cell 的 rowkey 进行映射，并将相应的数组位置为 1

#### 构建 Data Block
一个 cell 在内存中生成对应的布隆过滤器信息之后就会写入 Data Block，写入过程分为两步：
- Encoding KeyValue：使用特定的编码对 cell 进行编码处理，HBase 中主要的编码器有 DiffKeyDeltaEncoder、FastDiffDeltaEncoder 等。编码的基本思路是根据上一个 KeyValue 和当前 KeyValue 比较之后取 delta，即 rowkey、column、family 以及 column 分别进行比较然后取 delta。假如前后两个 KeyValue 和 rowkey 相同，当前 rowkey 就可以使用特定的一个 flag 标记，不需要再完整地存储整个 rowkey，这样可以极大地减少存储空间
- 将编码后的 KeyValue 写入 DataOutputStream

随着 cell 的不断写入，当前 Data Block 会因为大小超过阈值(默认 64K)而写满，写满后 DataBlock 会将 DataOutputStream 的数据 flush 到文件，该 Data Block 此时完成落盘。

#### 构建 Leaf Index Block
Data Block 完成落盘之后会立刻在内存中构建一个 Leaf Index Entry 对象，并将该对象加入到当前 Leaf Index Block。Leaf Index Entry 对象有三个重要的字段：
- fistKey：落盘 Data Block 的第一个 key，用来作为索引节点的实际内容，在索引树执行索引查找的时候使用
- blockOffset：落盘 Data Block 在 HFile 文件中的偏移量，用于索引目标确定后快速定位目标 Data Block
- blockDataSize：落盘 Data Block 的大小，用于定位到 Data Block 之后的数据加载

Leaf Index Block 会随着 Leaf Index Entry 的不断写入慢慢变大，一旦大小超过阈值(默认 64KB)，就需要 flush 到文件执行落盘。需要注意的是，Leaf Index Block 落盘是追加写文件的，所以就会形成 HFile 中 Data Block、Leaf Index Block 交叉出现的情况。

和 Data Block 落盘流程一样，Leaf Index Block 落盘之后还需要再往上构建 Root Index Entry 并写入 Root Index Block 形成索引树的根结点，但是根结点并没有追加写入 Scanned Block 部分，而是在最后写入 Load-on-open 部分。

HFile 文件中索引树的构建是由低向上发展的，先生成 Data Block，然后再生成 Leaf Index Block，最后生成 Root Index Block。而检索 rowkey 时先在 Root Index Block 中查找定位到某个 Leaf Index Block，再在 Leaf Inex Block 中二分查找定位到某个 Data Block，最后将 Data Block 加载到内存进行遍历查找

#### 构建 Bloom Block Index
完成 Data Block 落盘还有一件非常重要的事情：检查是否有已经写满的 Bloom Block，如果有将该 Bloom Block 追加写入文件，在内存中构建一个 Bloom Index Entry 并写入 Bloom Index Block。

flush 操作的不同触发方式对用户请求影响的程度不尽相同，正常情况下，大部分 MemStore Flush 操作都不会对业务读写产生太大影响，然而一旦触发 RegionServer 级别限制导致 flush，就会对用户请求产生较大的影响，在这中情况下系统会阻塞所有落在该 RegionServer 上的写入操作，直至 MemStore 中数据量降到配置阈值内。



### Bulk Load

如果有大量数据需要导入到 HBase 系统，此时调用 HBase API 进行处理极有可能会给 RegionServer 带来较大的写入压力：
- 引起 RegionServer 频繁 flush，进而不断 compact、split 影响集群稳定性
- 引起 RegionServer 频繁 GC，影响集群稳定性
- 消耗大量 CPU 资源、带宽资源、内存资源以及 IO 资源，与其他业务产生资源竞争
- 在某些场景下，比如平均 KV 大小比较大的场景，会耗尽 RegionServer 的处理线程，导致集群阻塞

HBase 提供了另一种将数据写入 HBase 集群的方法：BulkLoad。BulkLoad 首先使用 MapReduce 将待写入集群数据转换为 HFile 文件，再直接将这些 HFile 文件加载到在线集群中，BulkLoad 没有将写请求发送给 RegionServer 处理，可以有效避免 RegionServer 压力较大导致的问题。

BulkLoad 主要由两个阶段组成：
- HFile 生成阶段。这个阶段会运行一个 MapReduce 任务，mapper 方法将据组装成一个复合 KV，其中 key 是 rowkey，value 可以是 KeyValue 对象、Put 对象甚至 Delete 对象；reduce 方法由 HBase 负责，通过方法 HFileOutputFormat2.configureIncrementlLoad() 进行配置，这个方法主要负责以下事项：
  - 根据表信息配置一个全局有序的 partitioner
  - 将 partitioner 文件上传到 HDFS 集群并写入 DistributedCache
  - 设置 reduce task 的个数为目标表 Region 的个数
  - 设置输出 key-value 类满足 HFileOutputFormat 所规定的格式要求
  - 根据类型设置 reducer 执行相应的排序(KeyValueSortReducer 或者 PutSortReducer)
- HFile 导入阶段。HFile 准备就绪之后，就可以使用工具 complietebulkload 将 HFile 加载到在线 HBase 集群。complitebulkload 工具负责以下工作
  - 依次检查第一步生成的所有 HFile 文件，将每个文件映射到对应的 Region
  - 将 HFile 文件移动到对应 Region 所在的 HDFS 文件目录下
  - 告知 Region 对应的 RegionServer，加载 HFile 对外提供服务

如果在 BulkLoad 的中间过程中 Region 发生了分裂，completebulkload 工具会自动将对应的 HFile 文件按照新生成的 Region 边界切分成多个 HFile 文件，保证每个 HFile 都能与目标表当前 Region 相对应，但这个过程需要读取 HFile 内容，因而并不高效。需要尽量减少 HFile 生成阶段和 HFile 导入阶段的延迟，最好能够在 HFile 生成之后立刻执行 HFile 导入

通常有两种方法调用 completebulkload 工具：
```shell
bin/hbase org.apache.hadoop.hbase.tool.LoadIncrementalHFiles <hdfs://storefileoutput> <tablename>

bin/hadoop jar ${HBASE_HOME}/hbase-server-Version.jar completebulkload <hdfs://storefileoutput> <tablename>
```
如果表没在集群中，工具会自动创建表。