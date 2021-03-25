
## 故障转移

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