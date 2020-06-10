
## RegionServer
RegionServer 是 HBase 系统中最核心的组件，主要负责用户数据写入、读取等基础操作。RegionServer 包含多个模块：HLog、MemStore、HFile 以及 BlockCache。

RegionServer 是 HBase 系统响应读写请求的工作节点，一个 RegionServer 由一个或多个 HLog、一个 BlockCache 以及多个 Region 组成。其中 HLog 用来保证数据写入的可靠性；BlockCache 可以将数据块缓存在内存中以提升数据读取性能；Region 是 HBase 中数据表的一个数据分片，一个 RegionServer 上通常会负责多个 Region 的数据读写；一个 Region 由多个 Store 组成，每个 Store 存放对应列簇的数据，每个 Store 包含一个 MemStore 和多个 HFile，数据写入时会先将对应列簇数据写入相应的 MemStore，一旦写入数据的内存大小超过阈值，MemStore 就会 fulsh 到磁盘形成 HFile 文件。HFile 文件存放在 HDFS 上，是一种定制化格式的数据存储文件，方便进行数据读取。

### HLog
HBase 中系统故障恢复以及主从复制都是基于 HLog 实现。默认情况下，所有的写入操作(增加、更新和删除)的数据都先以追加形式写入 HLog，然后再写入 MemStore，当 RegionServer 异常导致 MemStore 中的数据没有 flush 到磁盘，此时需要回放 HLog 保证数据不丢失。此外，HBase 主从复制需要主集群将 HLog 日志发送给从集群，从集群在本地执行回放操作，完成集群之间的数据复制。

每个 RegionServer 默认拥有一个 HLog，1.1 版本后可以开启 MultiWAL 功能允许多个 HLog，每个 HLog 是多个 Region 共享的。HLog 中，日志单元 WALEntry 表示一次行级更新的最小追加单元，由 HLogKey 和 WALEdit 两部分组成，其中 HLogKey 由 tableName, regionName 以及 sequenceId 组成

HBase 中所有数据都存储在 HDFS 的指定目录(默认 /hbase)下，可以通过 hadoop 命令查看目录下与 HLog 有关的子目录：
```shell
hdfs dfs get /hbase
```
HLog 文件存储在 WALs 子目录下表示当前未过期的日志，同级子目录 oldWALs 表示已经过期的日志，WALs 子目录下通常有多个子目录，每个子目录代表一个 RegionServer，目录名称为 ```<domain>,<port>,<timestamp>```，子目录下存储对应 RegionServer 的所有 HLog 文件，通过 HBase 提供的 hlog 命令可以查看 HLog 中的内容：
```shell
./hbase hlog
```

HLog 文件生成之后并不会永久存储在系统中，HLog 整个生命周期包含 4 个阶段：
- HLog 构建：HBase 的任何写操作都会先将记录追加写入到 HLog 文件中
- HLog 滚动：HBase 后台启动一个线程，每隔一段时间(参数 ```hbase.regionserver.logroll.period``` 设置，默认 1 小时)进行日志滚动，日志滚动会新建一个新的日志文件，接收新的日志数据
- HLog 失效：写入数据一旦从 MemSotre 落盘到 HDFS 对应的日志数据就会失效。HBase 中日志失效删除总是以文件为单位执行，查看 HLog 文件是否失效只需要确认该 HLog 文件中所有日志记录对应的数据是否已经完成落盘，如果日志中所有记录已经落盘则可以认为该日志文件失效。一旦日志文件失效，就会从 WALs 文件夹移动到 oldWALs 文件夹，此时 HLog 文件并未删除
- HLog 删除：Master 后台会启动一个线程，每隔一段时间(参数 ```hbase.master.cleaner.interval``` 设置，默认 1 分钟)减产一次文件夹 oldWALs 下的所有失效日志文件，确认可以删除之后执行删除操作。确认失效可以删除由两个条件：
  - HLog 文件没有参与主从复制
  - HLog 文件在 oldWALs 文件夹中存在时间超过 ```hbase.master.logcleaner.ttl``` 设置的时长(默认 10 分钟)

### MemStore
HBase 中一张表被水平切分成多个 Region，每个 Region 负责自己区域的数据读写请求，每个 Region 包含所有列簇数据。HBase 将不同列簇的数据存储在不同的 Store 中，每个 Store 由一个 MemStore 和一系列的 HFile 组成。

HBase 基于 LSM 树模型实现，所有的数据写入操作首先会顺序写入日志 HLog 再写入 MemStore，当 MemStore 中数据大小超过阈值之后再将这些数据批量写入磁盘，生成一个新的 HFile 文件。

MemStore 使用跳跃表实现，即 JDK 自带的数据结构 ConcurrentSkipListMap，保证数据写入、查找、删除等操作在 O(lgN) 的时间复杂度内完成，ConcurrentSkipListMap 使用 CAS 操作保证了线程安全。

MemStore 由两个 ConcurrentSkipListMap 实现，写入操作会将数据写入到其中一个 ConcurrentSkipListMap，当数据超过阈值之后创建一个新的 ConcurrentSkipListMap 用于接收数据，之前写满的ConcurrentSkipListMap 会执行 flush 操作落盘形成 HFile

#### MemStore 内存管理
MemStore 本质是一块缓存，每个 RegionServer 由多个 Region 构成，每个 Region 根据列簇的不同包含多个 MemStore，者写 MemStore 都是共享内存的，如果 Region 上对应的 MemStore 执行 flush 操作则会使得内存空间出现较多的碎片，触发 JVM 执行 Full GC 合并这些内存碎片

为了优化这种内存碎片可能导致的 Full GC，HBase 借鉴了线程本地分配缓存(Thread-Local Allocation Buffer, TLAB)的内存管理方式，通过顺序化分配内存、内存数据分块等特性使得内存碎片更加粗粒度，有效改善 Full GC，这种内存管理方式称为 MemStore 本地分配缓存(MemStore-Local Allocation Buffer, MSLAB)其具体步骤为：
- 每个 MemStore 会实例化得到一个 MemStoreLAB 对象，MemStoreLAB 会申请一个 2M 大小的 Chunk 数组，同时维护一个 Chunk 偏移量，该偏移量的初始值为 0
- 当一个 k-v 值插入 MemStore 后，MemStoreLAB 首先通过 KeyValue.getBuffer() 取得 data 数据，并将 data 数组复制到 Chunk 数组中，之后再将 Chunk 偏移量往前移动 data.length
- 当 Chunk 满了之后，再调用 new byte[2*1024*1024] 申请一个新的 Chunk

MSLAB 将内存碎片粗化为 2M 大小，使得 flush 之后残留的内存碎片粒度更粗，从而降低 Full GC 的触发频率。但是这种内存管理方式还存在一个问题，当 Chunk 写满之后需要创建一个新的 Chunk，新创建的 Chunk 会在新生代分配内存，如果 Chunk 申请频繁则会导致频繁的 YGC。MemStore Chunk Pool 将申请的 Chunk 循环利用而不是在 flush 之后释放掉从而避免频繁的创建新的 Chunk 降低 YGC 的频率，具体步骤为：
- 系统创建一个 Chunk Pool 来管理所有未被引用的 Chunk 而不是被 JVM 回收
- 如果一个 Chunk 没有再被引用则将其放入 Chunk Pool
- 如果当前 Chunk Pool 已经达到了容量最大值，就不会接收新的 Chunk
- 如果需要申请新的 Chunk 来存储 k-v 则首先从 Chunk Pool 中获取，如果能够获取则重复使用 Chunk，否则就重新申请一个新的 Chunk

HBase 中 MSLAB 功能默认是开启的，可以通过参数 ```hbase.hregion.memstore.mslab.chunksize``` 设置 ChunkSize 的大小，默认是 2M，建议保持默认值；Chunk Pool 功能默认是关闭的，通过参数 ```hbase.hregion.memstore.chunkpool.maxsize``` 为大于 0 的值才能开启，默认是 0，该值得取值范围为 [0,1] 表示整个 MemStore 分配给 Chunk Pool 的总大小；参数 ```hbase.hregion.memstore.chunkpool.initialsize``` 取值为 [0,1] 表示初始化时申请多少个 Chunk 放到 Chunk Pool 里面，默认是 0

### 宕机恢复原理
HBase 系统中主要有两类服务进程：Master 进程和 RegionServer 进程。Master 主要负责集群管理调度，发生故障的概率比较低，RegionServer 负责用户的读写，压力会比较大，逻辑比较复杂，因此发生故障的概率较大。RegionServer 有一些常见的异常：
- Full GC 异常：长时间的 Full GC 是导致 RegionServer 宕机的最主要原因
- HDFS 异常：RegionServer 读写数据是直接操作 HDFS 的，HDFS 异常会导致 RegionServer 异常
- 机器宕机：物理节点宕机会导致 RegionServer 进程直接挂掉

HBase 采用了热备方式实现 Master 的高可用，集群中至少会启动两个 Master 进程，进程启动后会在 ZK 上的 Master 节点注册，注册成功后则会成为 Active Master，未注册成功则为 Backup Master，当 Active Master 异常，Backup Master 就会竞争注册成为 Active Master。

RegionServer 宕机之后 HBase 会在检测到宕机之后将该 RegionServer 上的所有 Region 重新分配到集群中其他正常的 RegionServer 上，再根据 HLog 进行数据丢失恢复，恢复完成后对外提供服务：
- Master 检测到 RegionServer 宕机：HBase 检测宕机是通过 ZK 实现的，正常情况下 RegionServer 会周期性的向 ZK 发送心跳，查过一定时间(SessionTimeout) 没有接收到心跳，ZK 就会认为 RegionServer 宕机离线，并将消息通知给 Master
- 切分未持久化数据的 HLog 日志：RegionServer 宕机之后已经写入 MemStore 但还没有持久化到文件的这部分数据会丢失，通过 HLog 可以恢复这部分数据。HLog 中所有Region 的数据都混合存储在同一个文件中，为了使这些数据能够按照 Region 进行组织回放，需要将 HLog 日志进行切分再合并，同一个 Region 的数据最终合并在一起，方便后续按照 Region 进行数据恢复
- Master 重新分配宕机 RegionServer 上的 Region：RegionServer 宕机后 Region 属于不可用状态，所有路由到这些 Region 的请求都会返回异常。Master 会将这些不可用的 Region 重新分配到其他 RegionServer 上，但此时这些 Region 还并没有上线，因为之前存储在 MemStore 中还没有落盘的数据需要回放
- 回放 HLog 日志补救数据：根据 HLog 按照 Region 切分的结果，在指定的 RegionServer 上回放，将还没有来得及落盘的数据补救回来
- 修复完成之后，对外提供服务，完成宕机恢复

#### RegionServer 宕机检测
HBase 使用 ZK 协助 Master 检测 RegionServer 宕机，所有 RegionServer 在启动之后都会在 ZK 节点 /rs 上注册一个子节点，这个子节点的类型为临时节点，一旦连接在该节点上的客户端因为某些原因发生会话超时，这个临时节点会自动消失，并通知 watch 在该临时节点上的其他客户端。

在一些情况下，由于 RegionServer 发生了长时间的 GC 导致心跳长时间断开，这是临时节点就会离线，导致 Master 认为 RegionServer 宕机。ZK 的会话超时时间可以在配置文件中设置，参数是 zookeeper.session.timeout，默认是 180s，但是此参数的调整需要配合 ZK 服务器的参数 tickTime, minSessionTimeout, maxSessionTimeout

#### 切分 HLog
一个 RegionServer 默认只有一个 HLog，RegionServer 中的所有 Region 的日志都是混合写入该 HLog 中，而日志回放时根据 Region 为单元进行的，因此需要将 HLog 中的数据按照 Region 进行分组，这个过程称为 HLog 切分。

HBase 最初阶段日志切分的整个过程由 Master 控制执行，整个切分合并流程分为三步：
- 将待切分日志文件重命名，避免 RegionServer 没有宕机而 Master 误认为 RegionServer 宕机，导致 HLog 日志在切分的同时还在不断的写入从而发生数据不一致
- 启动一个读线程依次顺序读出每个 HLog 中所有数据对，根据 HLog key 所属的 Region 写入不同的内存 buffer 中，这样整个 HLog 中的所有数据会完整的按照 Region 进行切分
- 切分完成之后，Master 会为每个 buffer 启动一个独立的写线程，负责将 buffer 中的数据写入各个 Region 对应的 HDFS 目录下。写线程会先将数据写入临时路径：/hbase/table_name/region/recoverd.edits/.tmp 之后重命名为正式路径 /hbase/table_name/region/recoverd.edits/.sequnceidx

切分完成之后，Region 重新分配到其他 RegionServer，最后按照顺序回放对应的 Region 的日志数据，这种日志切分的整个过程只有 Master 参与，在某些场景下需要恢复大量的数据，会加重 Master 的负载压力。

HBase 在后续版本中使用了 Distributed Log Spliting(DLS) 分布式切分 HLog 的机制，它借助 Master 和所有 RegionServer 进行日志切分，其中 Master 是协调者，RegionServer 是实际的工作者，其基本步骤如下：
- Master 将待切分日志路径发布到 ZK 节点 /hbaes/splitWAL 上，每个日志为一个任务，每个任务都有对应的状态，起始状态为 TASK_UNASSIGNED
- 所有 RegionServer 启动之后都注册在这个节点上等待新任务，一旦 Master 发布任务，RegionServer 就会抢占该任务
- 抢占任务实际上要先查看任务状态，如果是 TASK_UNASSIGNED 状态，说明当前没有被占有，如果修改失败，则说明其他 RegionServer 抢占成功
- RegionServer 抢占任务成功之后，将任务分发给相应线程处理，如果处理成功则将该任务对应的 ZK 节点状态修改为 TASK_DONE，如果处理失败则将状态改为 TASK_ERR
- Master 一直监听该 ZK 节点，一旦发生状态修改就会得到通知，如果任务状态变更为 TASK_ERR，则 Master 重新发布该任务；如果任务状态变更为 TASK_ERR，则 Master 将对应的节点删除

假设 Master 当前发布了 4 个任务，即当前需要回放 4 个日志文件，RegionServer 抢占到日志之后分别将任务分发给两个 HLogSplitter 线程进行处理，HLogSplitter 负责对日志文件执行具体的切分，首先读出日志中每一个数据对，根据 HLog key 所属的 Region 写入不同的 Region buffer。每个 Region buffer 都会有一个对应的线程，将 buffer 中的日志数据写入 hdfs 中，写入路径为 /hbase/table/region/sequenceid.temp，然后针对某一 Region 回放日志，将该 Region 对应的所有问及爱你按照 sequenceid 由小到大依次进行回放即可。

Distributed Log Splitting 方式可以很大程度上加快故障恢复的进程，通常可以将故障恢复时间降低到分钟级别，但是这种方式会产生很多日志小文件，产生的文件数将是 M*N(其中 M 是待切分的总 HLog 数量，N 是 RegionServer 上的 Region 个数)。

Distributed Log Replay(DLR) 方案相比于 DLS 方案有一些改动，Region 重新分配打开之后状态设置为 recovering，在此状态下的 Region 可以对外提供给写服务，不能提供读服务，而且不能执行 split, merge 等操作。DLR 方案在分解 HLog 为 Region buffer 之后并没有写入小文件，而是直接执行回放，这样可以大大减少小文件的读写 IO 消耗。DLR 方案需要在配置中通过设置参数 hbase.master.distributed.log.replay=true 来开启，同时 HFile 的格式也要设置为 v3

HBase 故障恢复经历的 4 个核心流程(故障检测、切分 HLog、Region 分配、回放 HLog 日志)中切分 HLog 最为耗时，如果一个 RegionServer 上维护了大量的 Region，当 RegionServer 宕机后需要将 HLog 切分成对应的数量，然后为每个 Region 分配一个 writer 写入到 HDFS。对于 HDFS 来说，每个写入 writer 需要消耗 3 个不同 DataNode 各一个 Xceiver 线程，而 HDFS 集群上每个 DataNode 的 Xceiver 的线程上限为 4096，若 Region 对应的 writer 过多则会导致 HDFS Xceiver 耗尽而导致故障恢复难以进行，从而对整个集群的可用性带来严重影响。

针对这个问题，首先应该控制的是 writer 的个数，为 HLog 中的每一个 Region 设一个缓冲池，每个 Region 的缓冲池有一个阈值上限 hbase.regionserver.hlog.splitlog.buffersize，如果碰到一条新的 HLog Entry 发现对应 Region 的缓冲池没有到达上限，则直接写缓冲，否则选出当前所有缓冲池中超过阈值的缓冲池结合，将这个集合中的缓冲池依次刷新成 HDFS 上的一个新文件，这个过程是放到一个 writer 池中完成，也就能保证任意时刻最多只有指定个数的 writer 在写数据文件而不会造成 Xceiver 被耗尽，副作用就是 split 操作之后产生的文件数变多。如果要开启这个功能需要配置：
```properties
hbase.split.create.writer.limited=true
hbase.regionserver.hlog.splitlog.buffersize=
hbase.regionserver.hlog.write.thread=32
```

另外从集群故障恢复的过程来看，其速度由几个变量决定：
- 故障 RegionServer 的个数
- 故障 RegionServer 上需要切分的 HLog 个数，由参数 hbase.hstore.blockingStoreFile 决定
- HDFS 集群 DataNode 个数
- 每个 RegionServer 上能并行跑的 split worker 个数，由参数 hbase.regionserver.wal.splitters 决定
- 每个 split worker 能开的 writer 线程个数，由 hbase.regionserver.hlog.splitlog.writer.thread 参数决定

由于需要满足 Xceiver 的个数不能超过 HDFS 集群的总数，提高并发只能通过调大每个 RegionServer 上并行的 split worker 或者每个 worker 能开启的 writer 值；对于需要尽快恢复集群，只能控制故障 RegionServer 上需要切分的 HLog 的个数，这个值不能设置的太大，也不能设置的太小，太大会导致故障恢复较慢，太小会导致 MemStore 频繁的进行 flush 操作，影响性能。

## 复制
复制功能为 HBase 跨集群数据同步提供了支撑。HBase 客户端创建一个 Peer（一条主集群到备份集群的复制链路），包含 PeerId, 备份集群的 ZK 地址、是否开启数据同步，以及需要同步的 namespace, table, column family 等，HBase 客户端创建 Peer 的流程如下：
- 将创建 Peer 的请求发送到 Master
- Master 内部实现了一个名为 Procedure 的框架，对于一个 HBase 的管理操作会拆分成多步，每步执行完成之后会将状态信息持久化到 HDFS 上，然后继续执行下一步操作，这样在任何一步出现异常就可以在这一步重新执行而不需要全部重新执行。对于创建 Peer 来说，Procedure 会为该 Peer 创建相关的 ZNode，并将复制相关的元数据保存在 ZK 上
- Master 的 Procedure 会向每一个 RegionServer 发送创建 Peer 的请求，直到所有的 RegionServer 都成功创建 Peer，否则会重试
- Master 返回给 HBase 客户端

在创建完 Peer 后，真正负责数据同步的是 RegionServer 的 ReplicationSource 线程，该线程复制数据的流程如下：
- 在创建 Peer 时，每个 RegionServer 会创建一个 ReplicationSource 线程，ReplicationSource 首先把当前写入的 HLog 都保存在复制队列中，然后再 RegionServer 上注册了一个 Listener，用来监听 HLog Roll 操作，如果 RegionServer 做了 HLog roll 操作，那么 ReplicationSource 收到这个操作后会把这个 HLog 分到对应的 walGroupQueue 里面，同时把 HLog 文件名持久化到 ZK 上，这样重启后还可以接着复制未复制完成的 HLog
- 每个 WalGroupQueue 后端有一个 ReplicationSourceWALReader 的线程，这个线程不断的从 Queue 中取出一个 HLog，然后把 HLog 中的 Entry 逐个读取出来，放到一个名为 entryBatchQueue 的队列中
- entryBatchQueue 后端有一个名为 ReplicationSourceShipper 的线程，不断从 Queue 中读取 Log Entry，交给 Peer 的 ReplicationEndpoint。ReplicationEndpoint 把这些 Entry 打包成一个 replicationWALEntry 操作，通过 RPC 发送到 Peer 集群的某个 RegionServer 上。对应 Peer 集群的 RegionServer 把 replicateWALEntry 解析成若干个 Batch 操作，并调用 batch 接口执行。待 RPC 调用成功后，ReplicationSourceShipper 会更新最近一次成功复制的 HLog Position 到 ZK，以便 RegionServer 重启后，下次能找到最新的 Position 开始复制

一个 Peer 可能存在多个 walGroupQueue，因为现在 RegionServer 为了实现更高的吞吐量允许同时写多个 WAL，同时写的多个 WAL 属于独立的 Group，所以在一个 Peer 内为每个 Group 设置一个 walGroupQueue。一种常见的场景是，每个业务设置一个独立的 namespace，然后每个 namespace 写自己独立的 WAL，不同的 WAL Group 通过不同的复制线程去推，这样如果某个业务复制阻塞了并不会影响其他的业务，因为不同的 namespace 产生的 HLog 会分到不同的 walGroupQueue。

复制的过程中有两个重要的信息存放在 ZK 上：
- Peer 相关信息，/hbase/replication/peers 目录下存放了 Peer 相关信息
- Peer 和 HLog 推送关系，/hbase/replication/rs 目录下记录了 Peer 的 HLog，以及 HLog 推送的 Postion

### 串行复制
非串行复制的情况下，如果 Region 在复制的时候发生了迁移，则可能导致两个 RegionServer 都会为 Peer 开辟一个复制线程，这样会带来几个问题：
- 源集群中写数据顺序和 Peer 集群中的执行顺序不一致
- 在极端情况下由于数据写入执行顺序乱序导致数据不一致(Delete 数据先于 Put 数据到达，导致 Put 数据不会被清除掉)

非串形复制的根本原因在于 Region 从一个 RegionServer 移动到另外一个 RegionServer 的过程中，Region的数据会分散在两个 RegionServer 的 HLog 上，而两个 RegionServer 完全独立地推送各自的 HLog，从而导致同一个 Region 的数据并行写入 Peer 集群。一个简单的解决思路是将 Region 的数据按照 Region 移动发生的时间切分为 N 段，在每段复制之前需要保证前面所有段的数据已经复制完成即可解决这个问题。

HBase 社区版根据这种思想提供了实现，包含三个概念：
- Barrier：每一次 Region 重新 Assign 到新的 RegionServer 时，新 RegionServer 打开 Region 前能读到的最大 SequenceId，因此每打开一次 Region 就会产生一个新的 Barrier，Region 在打开 N 次之后就会有 N 个 Barrier 把该 Region 的 SequenceId 数轴划分为 N+1 个区间
- LastPushedSequenceId：表示该 Region 最近一次成功推送到 Peer 集群的 HLog 的 SequenceId，事实上每次成功推送一个 Entry 到 Peer 集群后都需要将 LastPushedSequenceId 更新到最新的值
- PendingSequenceId：表示该 Region 当前读到的 HLog 的 SequenceId

HBase 集群值需要对每个 Region 都维护一个 Barrier 列表和 LastPushedSequenceId，就能按照规则确保在上一个区间的数据完全推送之后再推送下一个区间的数据，如果上一个区间的 HLog 还没有完全推送结束，就会休眠一段时间之后再检查一次上一个区间是否推送结束，若推送结束则开始推送本区间的数据

### 同步复制
HBase 的复制一般是异步的，即 HBase 客户端写入数据到主集群之后就返回，然后主集群再异步的把数据依次推送到备份集群。若主集群因意外或者 Bug 无法提供服务时，备份集群的数据是比主集群少的，通过同步复制的方式可以保证备份集群的数据和主集群的数据保持一致。

同步复制的核心思想是 RegionServer 在收到写入请求之后不仅会在主集群上写一份 HLog 日志，还会同时在备份集群上写一份 RemoteWAL 日志，在数据写入时只有主集群上的 HLog 日志和备集群上的 RemoteWAL 都写入成功之后才会返回给客户端。除此之外，主机群到备集群之间还会开启异步复制链路，若主集群的某个 HLog 通过异步复制完全推送到备份集群，那么这个 HLog 在备份集群上对应的 RemoteWAL 则被清理，因此可以认为 RemoteWAL 是指那些已经成功写入主集群但尚未被异步复制成功推送到备份集群的数据，对主集群的每次写入备份集群都不会丢失，当主集群发生故障，只需要回放 RemoteWAL 日志到备份集群，备份集群就可以马上恢复数据为线上提供服务。

为了方便同步复制，主集群和备集群的同步复制状态分为 4 种：
- Active：这种状态的集群将在远程集群上写 RemoteWAL 日志，同时拒绝接收来自其他集群的复制数据。一般情况下同步复制的主集群会处于 Active 状态
- Downgrade Active：这种状态的集群将跳过写 RemoteWAL 流程，同时拒绝接收来自其他集群的复制数据，一般情况下同步复制中的主集群因备份集群不可用卡住后，会被降级为 DA 状态用来满足业务的实时读写
- Standby：这种状态的集群不允许 Peer 内的表被客户端读写，它只接收来自其他集群的复制数据，同时确保不会将本集群中 Peer 内的表数据复制到其他集群上，一般情况下同步复制中的备份集群会处于 Standby 状态
- Node：表示没哟开启同步复制

#### 建立同步复制
建立同步复制分为三步：
- 在主集群和备份集群分别建立一个指向对方集群的同步复制 peer，这是主集群和备份集群的状态默认为 DA
- 通过 transit_peer_sync_replication_state 命令将备份集群的状态从 DA 切换成 Standby
- 将主集群状态从 DA 切换成 Active

#### 备份集群故障处理
当备份集群发生故障时，处理流程如下：
- 将主机群状态从 Active 切换为 DA，此时备份集群已经不可用，所有写到主集群的请求可能会因为写 RemoteWAL 失败而失败，因此需要将主集群从 Active 切换为 DA，这样就不需要写 RemoteWAL 了，保证了业务能正常读写 HBase 集群
- 在确保备份集群恢复后，直接把备份集群状态切换为 S，在备份集群恢复之前的数据通过异步复制同步到了备份集群，恢复之后的数据通过同步复制的方式同步到备份集群
- 将主机群状态从 DA 切换成 Active

#### 主集群故障处理
当主集群发生故障时，处理流程如下：
- 将备份集群的状态从 Standby 切换成 DA，之后备份集群不再接收来自主集群复制过来的数据，在备份集群状态从 Standby 切换成 DA 的过程中会先回放 RemoteWAL 日志，保证主备集群数据一致性后再让业务方把读写流量都切换到备份集群
- 主集群恢复后，虽然业务已经切换到原来的备份集群上，但是原来的主集群还认为自己是 Active 状态
- 由于主集群认为自己是 Active 状态，所有备份集群上的数据不能同步到主集群，这时可以直接将主集群状态切换成 Standby，等原来的备份集群上的数据都同步到主集群之后，两个集群的数据将最终保持一致
- 把备份集群从 DA 切换成 A，继续开启同步复制保持数据一致性


同步复制在数据最终一致性和集群可用性方面比异步复制更加优秀，但是由于需要写一份 RemoteWAL 导致写性能有所下降(13%)、网络带宽以及存储空间更大，且整个过程也更加复杂。

## 备份与恢复
Snapshot 是 HBase 的一个核心功能，使用在线 Snapshot 备份可以实现增量备份和数据迁移。HBase 提供了 4 个在线 Snapshot 备份与恢复的工具：
- snapshot：为表生成一个快照，但并不涉及数据移动，可以在线完成 ```snapshot 'sourceTable', 'snapshotName'```
- restore_snapshot：恢复指定快照，恢复过程会替代原有数据，将表还原到快照点，快照点之后的所有更新将会丢失 ```resotre_snapshot 'snapshotName'
- clone_snapshot：根据快照恢复出一个新表，恢复过程不涉及数据移动，可以在秒级完成 ```clone_snapshot 'snapshotName', 'tableName'```
- ExportSnapshot：可以将一个集群的快照数据迁移到另一个集群，ExportSnapshot 是 HDFS 层面的操作，需要用 MapReduce 进行数据的并行迁移，因此需要两个集群支持 MapReduce。Master 和 RegionServer 不参与迁移过程，因此不会带来额外的内存开销以及 GC 开销。ExportSnapshot 针对 DataNode 需要额外的带宽以及 IO 负责问题设置了 bandwidth 来限制带宽的使用 ```hbase.org.apache.hadoop.hbase.snapshot.ExportSnapshot -snapshot MySnapshot -copy-from hdfs://srv2.8082/hbase -copy-to hdfs://srv1:50070/hbase -mappers 16 -bandwidth 1024```

Snapshot 机制不会拷贝数据，而是类似于原始数据的一份指针，HBase 的 snapshot 只需要为当前表的所有文件分别新建一个引用，对于其他新写入的数据，重新创建一个新文件写入即可。snapshot 流程主要涉及两个步骤：
- 将 MemStore 中的缓存数据 flush 到文件中
- 为所有 HFile 文件分别新建引用指针，这些指针元数据就是 snapshot

HBase 为指定表执行 snapshot 操作时，实际上真正执行 snapshot 的是对应表的所有 Region，HBase 使用二阶段提交(Two-Phase Commit, 2PC)协议来保证这些 Region 在多个不同的 RegionServer 上要么都完成，要么都失败。2PC 一般由一个协调者和多个参与者组成，整个事务提交过程如下：
- prepare 阶段协调者会向所有参与者发送 prepare 命令
- 所有参与者接收到命令，获取相应资源，执行 prepare 操作确认可以执行成功，一般情况下核心工作都是在 prepare 操作中完成
- 返回给协调者 prepared 应答
- 协调者接收到所有参与者返回的 prepared 应答，表示所有参与者都已经准备好提交，然后在本地持久化 committed 状态
- 持久化完成之后进入 commit 阶段，协调者会向所有参与者发送 commit 命令
- 参与者接收到 commit 命令，执行 commit 操作并释放资源，通常 commit 操作都比较简单
- 返回给协调者

prepare 阶段：
- Master 在 ZK 创建一个 /acquired-snapshotname 节点，并在此节点上写入 Snapshot 相关信息
- 所有 RegionServer 检测到这个节点，根据这个节点的 snapshot 信息查看当前 RegionServer 上是否有目标表，如果不存在则忽略，如果存在则遍历目标表中的所有 Region，针对每个 Region 分别执行 snapshot 操作。此时 snapshot 操作的结果并没有写入最终文件夹，而是写入临时文件夹
- RegionServer 执行完成之后会在 /acquired-snapshotname 节点下新建一个子节点 /acquired-snapshotname/nodex，表示 nodex 节点完成了该 RegionServer 上所有相关 Region 的 snapshot 准备工作

commit 阶段：
- 一旦所有 RegionServer 都完成了 snapshot 的 prepare 工作，即都在 /acquired-snapshotname 节点下新建一个子节点 /acquired-snapshotname/nodex 节点，Master 就认为 Snapshot 的准备工作完全完成，Master 会创建一个新的节点 /reached-snapshotname，表示发送一个 commit 命令给参与的 RegionServer
- 所有 RegionServer 检测到 /reached-snapshotname 节点之后，执行 commit 操作，即将 prepare 阶段生成的结果从临时文件夹移动到最终文件夹即可
- 在 /reached-snapshotname 节点下创建子节点 /reached-snapshotname/nodex，表示节点 nodex 完成 snapshot 工作

abort 阶段：
- 如果在一定时间内 /acquired-snapshotname 没有满足条件，即还有 RegionServer 的准备工作没有完成，Master 认为 snapshot 的准备工作超时，Master 认为 snapshot 的准备工作超时，Master 会新建另一个新节点 /abort-snapshotname，所有 RegionServer 监听到这个命令之后会清理 snapshot 在临时文件夹中生成的结果

在整个 snapshot 过程中，Master 充当了协调者的角色，RegionServer 充当了参与者的角色，Master 和 RegionServer 之间的通信通过 ZK 来完成，同时事务状态也记录在 ZK 的节点上，Master 在高可用的情况下即使发生了切换也会根据 ZK 上的事务状态决定是否继续提交或者回滚

snapshot 在两阶段提交过程中 RegionServer 实现 snapshot 的过程分为三步：
- 将 MemStore 中数据 flush 到 HFile
- 将 region info 元数据(表名、startKey 和 endKey) 记录到 snapshot 文件中
- 将 Region 中所有的 HFile 文件名记录到 snapshot 文件中

Master 会在所有 Region 完成 snapshot 之后执行一个汇总操作，将所有 region snapshot manifest 汇总成一个单独的 manifest 存放在 HDFS 目录 /hbase/.hbase-snapshot/snapshotname/data.menifest，该目录下有三个文件，其中 .snapshotinfo 为 Snapshot 基本信息，包含待 snapshot 的表名称以及 snapshot 名；data.manifest 为 snapshot 执行后生成的元数据信息，即 snapshot 结果信息

clone_snapshot 过程分为六步：
- 预检查，确认当前目标表没有执行 snapshot 以及 restore 等操作，否则直接返回错误
- 在 tmp 文件夹下新建目标表目录并在表目录下新建 .tabledesc 文件，在该文件中写入表 schema 信息
- 新建 region 目录，根据 snapshot manifest 中的信息新建 Region 相关目录以及 HFile 文件
- 将表目录从 tmp 文件夹下移到 HBase Root Loacation
- 修改 hbase:meta 表，将克隆表的 Region 信息添加到 hbase:meta 表中，注意克隆表的 Region 名称和原数据表的 Region 名称并不相同
- 将这些 Region 通过 round-robin 方式均匀分配到整个集群中，并在 ZK 上将克隆表的状态设置为 enabled，正式对外提供服务

clone_snapshot 工具克隆表的过程并不涉及数据的移动，而是使用了 LinkFile 的文件指向了源文件，LinkFile 文件本身没有任何内容，它的所有核心信息都包含在它的文件名中，通过文件名可以定位到原始文件的具体路径

snapshot 实际上是一系列原始表的元数据，主要包括表 schema 信息、原始表的所有 Region 的 regioninfo 信息，如果原始表发生了 Compaction 导致 HFile 文件名发生了变化或者 Region 发生了分裂等，HBase 会在原始表发生 compaction 操作前将原始表数据复制到 archive 目录下再执行 compact，这样 snapshot 对应的元数据就不会失去意义，只不过原始数据不再存放于数据目录下，而是移动到了 archive 目录下


## HBase 运维

HBCK(HBaseFsck) 工具可以检测 HBase 集群中 Region 的一致性和完整性，同时可以对损坏的集群进行修复。

使用 hbck 命令可以检查集群是否存在损坏：
```shell
./bin/hbase hbck
```
如果检查到 Region 不一致状态则会在窗口输出基本报告信息，使用 -detail 可以查看细节，同时该命令也可以指定表来指定检查范围。

HBCK 可以恢复集群不一致的问题，Region 一致性问题修复有两个基本选项：
- fixAssignments：修复 assign 相关问题，如没有 assign、assign 不正确或者同时 assign 到多台 RegionServer 上
- fixMeta：主要修复 .regioninfo 文件和 hbase:meta 元数据表的不一致，修复原则是以 HDFS 文件为准

### HBase 核心参数配置
- hbase.hregion.max.filesize：默认为 10G，表示 Region 中最大的 Store 中所有文件大小阈值，一旦超过这个值整个 Region 就会执行分裂。太大会导致在执行 compaction 的时候消耗大量系统资源，太小导致 Region 分裂频繁，过多 Region 在故障恢复时比较耗时
- hfile.block.cache.size：默认 0.4，用于设置 LRUBlockCache 的内存大小占 JVM 内存的比例
- hbase.bucketcache.ioengine：BucketCache 策略模式选择，可选项有 heap, offheap 和 file 三种，分别表示使用堆内存、堆外内存和 SSD 作为缓存存储介质
- hbase.bucketcache.size：堆外内存大小，依赖于物理内存大小
- hbase.hregion.memstore.flush.size：默认为 128M，大于该阈值 MemStore 就会 flush，阈值过小则会频繁 flush 造成小文件过多，调大阈值会导致宕机恢复时需要 split 的 HLog 数量变多从而延长故障恢复时间
- hbase.hregion.memstore.block.multiplier：默认为 4，表示一旦某 Region 中所有写入 MemStore 的数据大小总和达到或超过阈值 hbase.hregion.memstore.block.multiplier * hbase.hregion.memstore.flush.size 就会触发 flush 操作
- hbae.regionserver.global.memstore.size：默认为 0.4，表示占用总 JVM 内存大小的 40%，整个 RegionServer 上所有写入 MemStore 的数据大小总和超过这个阈值就会强制执行 flush 操作


### HBase 表设计
生产线创建表时不要单独使用表名，而应该使用命名空间加表名的形式，同一业务的相关表放在同一个命名空间下，不同业务使用不同的命名空间

列簇可以根据业务需要配置不同的属性：
- VERSIONS：系统保留的最大版本，默认为 1，数据的版本一旦超过该值老的版本就会被删除
- BLOCKCACHE：是否开启 BlockCache，默认为 true，表示数据 Block 从 HFile 加载出来之后会被放入读缓存；如果设置为 false，则读取的数据 Block 不会放入读缓存，在数据量很大且读取没有任何热点或者表中的数据仅供 OLAP 分析而没有 OLTP 业务时可以设置为 false
- BLOOMFILTER：指定布隆过滤器的类型，可选项有 NONE、ROW 和 ROWCOL，默认为 ROW。ROW 根据 rowkey 判断待查找的数据是否存在于 HFile，而 ROWCOL 模式只对指定列的随机读有优化作用
- TTL：设置数据失效时间，数据自动失效可以减少数据规模，提升查询性能。TTL 过期的数据是通过 Compaction 机制进行删除的
- COMPRESSION：指定数据压缩算法，可选项有 NONE、SNAPPY、LZ4 和 ZLTD。数据压缩可以减少数据量，进而减少 IO，但是数据的压缩和解压缩会消耗 CPU 资源。HBase 的数据压缩是 Block 级别的，生产线上推荐使用 SNAPPY 压缩算法
- DATA_BLOCK_ENCODING：指定数据编码算法，可选项有 NONE、PREFIX、DIFF、FASTDIFF 和 PREFIX_TREE，数据编码同样可以减少数据量，也同样会消耗 CPU 资源
- BLOCKSIZE：指定文件块大小，默认 64K。Block 是 HBase 存储的最小粒度，读数据时会先读出整个 Block，然后再遍历出需要的数据，在 get 请求较多时可以调小 BLOCKSIZE，如果 scan 请求较多时可以调大 BLOCKSIZE
- DFS_REPLICATION：数据 Block 在 HDFS 上存储的副本数，默认和 HDFS 文件系统设置相同。DFS_REPLICATION 可以让不同的列簇数据在 HDFS 上拥有不同的副本数，对于某些列簇数据量较大但是数据不重要可以调低
- IN_MEMORY：如果表中某些列的数据量不大，但是进行 get 和 scan 的频率特别高，使用 IN_MEMORY 可以使得 get 和 scan 延迟更低

在创建表时也可以设置表级的属性：
- 预分区：预分区在 HBase 中非常重要，不经过预分区的业务表在后期会出现数据分布极度不均衡的情况，从而造成读写请求不均衡。与预分区有关的两个属性为 NUMREGIONS 和 SPLITALGO，其中 NUMREGIONS 表示预分区的个数，该属性由数据量、Region 大小等因素决定；SPLITALGO 表示切分策略，可选项有 UniformSplit 和 HexStringSplit 两种，HexStringSplit 适用于 rowkey 前缀时十六进制字符串场景，UniformSplit 适用于 rowkey 时随机字节数组的场景，也可以通过实现 RegionSplitter.SplitAlgorithm 接口自定义切分策略
- MAX_FILESIZE：最大文件大小，功能与配置文件中 "hbase.hregion.max.filesize" 的配置相同，默认为 10G，主要影响 Region 切分的时机
- READONLY：设置表只读，默认 false
- COMPACTION_ENABLED：设置是否开启 Compaction，默认为 true，表示允许 Minor/Major Compaction 自动执行
- MEMSTORE_FLUSHSIZE：单个 MemStore 大小，与 hbase.hregion.memstore.flush.size 配置相同，默认 128M
- DURABLITY：WAL 持久化等级，默认为 USER_DEFAULT，可选项有 SKIP_WAL、ASYNC_WAL 以及 FSYNC_WAL，在允许异常情况下部分数据丢失时可以设置为 SKIP_WAL 或者 ASYNC_WAL

### Procedure 框架
一个 Procedure 由多个 subtask 组成，每个 subtask 是一些执行步骤的集合，这些执行步骤中又依赖部分 Procedure。Procedure 提供了两个方法：execute() 和 rollback()，用于实现 Procedure 的执行逻辑和回滚逻辑，这两个方法的实现需要保证幂等性。

以建表的 Procedure 为例，讨论 Procedure V2 的原子性执行和回滚：
- Procedure 引入了 Procedure Store 的概念，Procedure 内部的任何状态变化，或者 Procedure 的子 Procedure 状态变化，或者从一个 subtask 转移到另一个 subtask 都会被持久化到 HDFS 中，持久化的方式是在 Master 的内存中维护一个实时的 Procedure 镜像，然后有任何更新都把更新顺序写入 Procedure WAL 日志中
- Procedure 引入了回滚栈和调度队列的概念，回滚栈是一个记录 Procedure 的执行过程的栈结构，当需要回滚的时候则依次出栈执行过程进行回滚；调度队列是 Procedure 调度执行时的双向队列 

### In Memory Compaction
HBase 2.x 版本引入了 Segment 的概念，本质上是一个维护一个有序的 cell 列表，根据 cell 列表是否可更改，Segment 可以分为两种类型：
- MutableSegment：支持添加 cell、删除 cell、扫描 cell、读取某个 cell 等操作，一般使用 ConcurrentSkipListMap 来维护
- ImmutableSegment：只支持扫描 cell 和读取某个 cell 这种查找类操作，不支持添加、删除等写入操作，只需要一个数据维护即可

HBase 还引入了 CompactingMemStore，将原来的 128M 大小的 MemStore 划分成很多个小的 Segment，其中一个 MutableSegment 和多个 ImmutableSegment。Column Family的写入操作时，先写入 MutableSegment，一旦发现 MutableSegment 占用的空间超过 2 MB，则把当前 MutableSegment 切换成 ImmutableSegment，然后初始化一个新的 MutableSegment 供后续的写入。

CompactingMemStore 中的所有 ImmutableSegment 称为 pipeline，本质上是按照 ImmutableSegment 加入的顺序组成的一个 FIFO 队列。当 Column Family 发起读取或者扫描操作时，需要将这个 CompactingMemStore 的一个 MutableSegment、多个 ImmutableSegment 以及磁盘上的多个 HFile 组织成多个内部数据有序的 Scanner，然后将这些 Scanner 通过多路归并算法合并生成可以读取 Column Family 数据的 Scanner。

随着数据的不断写入，ImmutableSegment 个数不断增加，需要多路归并的 Scanner 就会很多，降低读取操作的性能。所以当 ImmutableSegment 个数达到某个阈值(hbase.hregion.compacting.pipeline.segments.limit 设置，默认值 2)时，CompactingMemStore 会触发一次 InMemtory 的 MemStoreCompaction，也就是将 CompactingMemStore 的所有 ImmutableSegment 多路归并成一个 ImmutableSegment，这样 CompactingMemStore 产生的 Scanner 数量就会得到很好地控制，对杜兴能基本无影响。

ImmutableSegment 的 Compaction 同样会清理掉无效的数据，包括 TTL 过期数据、超过指定版本的数据、以及标记为删除的数据，从而节省了内存空间，使得 MemStore 占用的内存增长变缓，减少 MemStore Flush 的频率。

CompactingMemStore 中引入 ImmutableSegment 之后使得更多的性能优化和内存优化得到可能。ImutableSegment 需要维护的有序列表不可变，因此可以直接使用数组而不再需要使用跳跃表(ConcurrentSkipListMap)，从而节省了大量的节点开销，也避免了内存碎片。

相比 DefaultMemStore，CompactingMemStore 触发 Flush 的频率会小很多，单次 Flush 操作生成的 HFile 数据量会变大，因此 HFile 数量的增长就会变慢，这样至少有三个好处：
- 磁盘上 Compaction 的触发频率降低，HFile 数量少了，无论是 Minor Compaction 还是 Major Compaction 次数都会降低，这会节省很大一部分磁盘带宽和网络带宽
- 生成的 HFile 数量变少，读取性能得到提升
- 新写入的数据在内存中保留的时间更长，对于写完立即读的场景性能会有很大提升

使用数组代替跳跃表后，每个 ImmutableSegment 仍然需要在内存中维护一个 cell 列表，其中每一个 cell 指向 MemStoreLAB 中的某一个 Chunk。可以把这个 cell 列表顺序编码在很少的几个 Chunk 中，这样 ImmutableSegment 的内存占用可以进一步减少，同时实现了零散对象“凑零为整”，进一步减少了内存的占用。

可以在配置文件 hbase-site.xml 中配置集群中所有表都开启 In Memory Compaction 功能：
```xml
hbae.hregion.compacting.memstore.type=BASIC
```
也可以在创建表的时候指定开启 In Memory Compaction 功能：
```shell
create 'test', {NAME=>'cf', IN_MEMORY_COMPACTION=>'BASIC'}
```
In Memory Compaction 有三种配置值：
- NONE：默认值，表示不开启 In Memory Compaction 功能，而使用 DefaultMemStore
- BASIC：开启 In Memory Compaction 功能，但是在 ImmutableSegment 上 Compaction 的时候不会使用 ScanQueryMatcher 过滤无效数据，同时 cell 指向的内存数据不会发生任何移动
- EAGER：开启 In Memory Compaction 功能，在 ImmutableSegment Compaction 的时候会通过 ScanQueryMatcher 过滤无效数据，并重新整理 cell 指向的内存数据，将其拷贝到一个全新的内存区域

如果表中存在大流量特定行的数据更新，则使用 EAGER，否则使用 BASIC。

### MOB 对象存储

### HBase 社区流程
- 创建 isuue 描述相关背景：地址为 https://issues.apache.org/jira/browse/HBASE  初次使用需要发邮件给 dev@hbase.apache.org，开通 Jira 的 Contributor 权限
- 将 issue assign 给自己
- 将代码修改导出 patch，并 attach 到 issue 上，代码提交到本地 git 仓库后，通过 git format -l 将最近一次提交的代码导出到 patch 文件中，并将文件命名为 <issue-id>.<branch>.v1.patch，然后可以在 github 提交 pull request
- 将 issue 状态更改为 path available
- HadoopQA 自动为新提交的 Patch 跑所有 UT
- 待 UT 通过后，请求 Commiter/PMC 代码 review
- 完成代码修改，并获得 Commiter/PMC 的 +1 认可
- 注意编写 issue 的 Release Note 内容，告诉未来使用新版本 HBase 的用户，本次修改主要对用户有何影响
- commiter/PMC 将代码提交到官方 git
- 将 issue 状态设为 Resolved




### 版本(Versions)
行和列键表示为字节，而版本则使用长整数指定；HBase 的数据是按照 vesion 降序存储的，所以在读取数据的时候读到的都是最新版本的数据。

列存储的最大版本数在创建表的时候就需要指定，在 HBase 0.96 之前默认最大版本数为 3，之后更改为 1。可以通过 alter 命令或者 HColumnDescriptor.DEFAULT_VERSIONS 来修改。
```shell
# 设置 t1 表的 f1 列族的最大存储版本为 5
alter 't1', Name=>'f1', VERSIONS=>5
```
也可以指定列族的最小存储版本，默认是 0 即该功能不启用：
```shell
# 设置 t1 表的 f1 列族的最小存储版本为 2
alter 't1', NAME=>'f1', MIN_VERSIONS=>2
```
### HBase Schema
- Region 的大小控制在 10-50 GB
- Cell 的大小不要超过 10M，如果有比较大的数据块建议将数据存放在 HDFS 而将引用存放在 HBase
- 通常每个表有 1 到 3 个列族
- 一个表有 1-2 个列族和 50-100 个 Region 是比较好的
- 列族的名字越短越好，因为列族的名字会和每个值存放在一起

#### hbase:meta
hbase:meta 和其他 HBase 表一样但是不能在 Hbase shell 通过 list 命令展示。hbase:meta表（以前称为.meta.）保存系统中所有 Region 的列表，hbase:meta 表的存放地址保存在 zookeeper 上，需要通过 zookeeper 寻址。

hbase:meta 存放着系统中所有的 region 的信息，以 key-value 的形式存放：
- key 以 ([table],[region start key],[region id]) 表示的 Region，如果 start key 为空的则说明是第一个 Region，如果 start key 和 end key 都为空则说明只有一个 Region
- value 保存 region 的信息，其中 info:regioninfo 是当前 Regsion 的 HRegionInfo 实例的序列化；info:server 是当前 Region 所在的 RegionServer 地址，以 server:port 表示；info:serverstartcode 是当前 Region 所在的 RegionServer 进程的启动时间

当 HBase 表正在分裂时，info:splitA 和 info:splitB 分别代表 region 的两个孩子，这些信息也会序列化到 HRegionInfo 中，当 region 分裂完成就会删除。

### Hbase 操作
#### Schema 操作
HBase 的 Schema 通过 Admin 对象来创建；在修改列族之前，表必须是 disabled；对表或者列族的修改需要到下一次主合并并且 StoreFile 重写才能生效。
```java
Configuration conf = HBaseConfiguration.create();
Connnection conn = ConnectionFactory.createConnection(conf);

Admin admin = conn.getAdmin();

TableName table = TableName.valueOf("table_name");
admin.disableTable(table);

ColumnFamilyDescriptor descriptor = ColumnFamilyDescriptorBuilder
	.newBuilder("column_family".getBytes())
	.setMaxVersions(1)
	.build();
admin.addColumnFamily(table, descriptor);
admin.modifyColumnFamily(table, descriptor);

admin.enableTable(table);
```
#### 数据操作
#### Get
默认在 Get 操作没有显式指定版本的时候的到的是最新版本的数据，可以在 Get 的时候设置版本相关参数：
- Get.setMaxVersion() - 设定返回多个版本的数据
- Get.setTimeRange() - 设置返回指定版本的数据
```shell
get '<table_name>', '<row_key>'
```
```java
Get get = new Get()
```
#### Put
Put 操作田间一行数据到表(行键不存在)或者更新已经存在的行(行键已存在)；Put 操作通过 Table.put 或者 Table.batch 完成。

每次 Put 操作都会创建一个新版本的 Cell，默认情况下系统使用 ```currentTimeMillis```，可以在 Put 的时候指定版本，但是系统使用时间戳作为版本为了计算 TTL，因此最好不要自行设置版本。
```shell
```
```java
```
#### Scan
Scan 可以在指定属性下迭代多行。
```shell
```
```java
```
#### Delete
Delete 通过 Table.delete 来删除表中的一行。

HBase 不会立马修改数据，因此是通过创建名为“墓碑”的标记在主合并的时候连同数据一起被清除。

删除操作也可以指定版本，如果没有指定则删除所有的版本。
```shell
```
```java
```

### Ref
- [HTAP(Hybrid Transaction and Analytical Processing)]()
- [空间局部性]()
- [时间局部性]()