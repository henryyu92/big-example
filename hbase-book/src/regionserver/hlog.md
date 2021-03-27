### HLog

HLog 是 HBase 中 WAL (Write Ahead Log) 的实现，通常用于数据恢复和数据复制。默认情况下，HLog 会记录下所有的数据变更到 HDFS，当 RegsionServer 发生异常时通过回放 HLog 可以恢复写入到 MemStore 但是还未刷盘到 HFile 的数据。

每个 RegionServer 只有一个 HLog，RegionServer 上的所有 Region 共用同一个 HLog。所有的写操作 (Put 和 Delete) 都会先追加到 HLog 然后再写入 MemStore。

### HLog 文件

`WALEntry` 表示一次行级更新的最小追加单位，由 `HLogKey` 和 `WALEdit` 组成，其中 `HLogKey` 由 sequenceid, writetime, clusterid, regionname 和 tablename 组成，sequenceid 是日志写入时分配的自增序列。

![hlog]()

HBase 中所有数据都存储在 HDFS 的指定目录(默认 /hbase)下，可以通过 hadoop 命令查看目录下与 HLog 有关的子目录：
```shell
hdfs dfs get /hbase
```
HLog 文件存储在 WALs 子目录下表示当前未过期的日志，同级子目录 oldWALs 表示已经过期的日志，WALs 子目录下通常有多个子目录，每个子目录代表一个 RegionServer，目录名称为 ```<domain>,<port>,<timestamp>```，子目录下存储对应 RegionServer 的所有 HLog 文件，通过 HBase 提供的 hlog 命令可以查看 HLog 中的内容：
```shell
./hbase hlog
```

### 生命周期

HLog 文件生成之后并不会永久存储在系统中，HLog 整个生命周期包含 4 个阶段：

- HLog 构建：HBase 的任何写操作都会先将记录追加写入到 HLog 文件中
- HLog 滚动：HBase 后台启动一个线程，每隔一段时间(参数 ```hbase.regionserver.logroll.period``` 设置，默认 1 小时)进行日志滚动，日志滚动会新建一个新的日志文件，接收新的日志数据
- HLog 失效：写入数据一旦从 MemSotre 落盘到 HDFS 对应的日志数据就会失效。HBase 中日志失效删除总是以文件为单位执行，查看 HLog 文件是否失效只需要确认该 HLog 文件中所有日志记录对应的数据是否已经完成落盘，如果日志中所有记录已经落盘则可以认为该日志文件失效。一旦日志文件失效，就会从 WALs 文件夹移动到 oldWALs 文件夹，此时 HLog 文件并未删除
- HLog 删除：Master 后台会启动一个线程，每隔一段时间(参数 ```hbase.master.cleaner.interval``` 设置，默认 1 分钟)减产一次文件夹 oldWALs 下的所有失效日志文件，确认可以删除之后执行删除操作。确认失效可以删除由两个条件：
  - HLog 文件没有参与主从复制
  - HLog 文件在 oldWALs 文件夹中存在时间超过 ```hbase.master.logcleaner.ttl``` 设置的时长(默认 10 分钟)

### HLog 切分

RegionServer 中的所有 Region 的日志都是混合写入该 HLog 中，而日志回放时根据 Region 为单元进行的，因此需要将 HLog 中的数据按照 Region 进行分组，这个过程称为 HLog 切分。

HLog 切分发生在集群启动或者由于 RegionServer 异常而需要进行故障迁移，为了保证一致性，在对应的 WALEdit 回放完成之前，Region 是不可用的。

HLog 日志切分过程由 Master 控制，整个流程包含三个步骤：

- 将待切分日志文件重命名为 `/hbase/WALs/<host>,<port>,<startcode>-splitting`，避免因 HMaster 误认为 RegionServer 宕机而触发日志切分时数据的不一致
- 启动线程依次读取 `WALEntry`，并根据 `HLogKey` 中的 Region 信息将其存放到对于的缓冲，后台写线程会将缓冲中的数据写入到临时文件 `/hbase/<table_name>/<region_id>/recoverd.edits/.temp` 中
- 当日志切分完成后将临时文件重命名为 `/hbase/<table_name>/<region_id>/recoverd.edits/.<sequnceidx>`

### Distributed Log Spliting

Distributed Log Split 是 Log Spliting 的分布式实现，它借助 Master 和所有 RegionServer 进行日志切分，其中 Master 是协调者，RegionServer 是实际的工作者，其基本步骤如下：

- Master 将待切分日志路径发布到 ZK 节点 /hbaes/splitWAL 上，每个日志为一个任务，每个任务都有对应的状态，起始状态为 TASK_UNASSIGNED
- 所有 RegionServer 启动之后都注册在这个节点上等待新任务，一旦 Master 发布任务，RegionServer 就会抢占该任务
- 抢占任务实际上要先查看任务状态，如果是 TASK_UNASSIGNED 状态，说明当前没有被占有，此时就去修改该节点状态为TASK_OWNED，如果修改失败，则说明其他 RegionServer 抢占成功
- RegionServer 抢占任务成功之后，将任务分发给相应线程处理，如果处理成功则将该任务对应的 ZK 节点状态修改为 TASK_DONE，如果处理失败则将状态改为 TASK_ERR
- Master 一直监听该 ZK 节点，一旦发生状态修改就会得到通知，如果任务状态变更为 TASK_ERR，则 Master 重新发布该任务；如果任务状态变更为 TASK_DONE，则 Master 将对应的节点删除

假设 Master 当前发布了 4 个任务，即当前需要回放 4 个日志文件，RegionServer 抢占到日志之后分别将任务分发给两个 HLogSplitter 线程进行处理，HLogSplitter 负责对日志文件执行具体的切分，首先读出日志中每一个数据对，根据 HLog key 所属的 Region 写入不同的 Region buffer。每个 Region buffer 都会有一个对应的线程，将 buffer 中的日志数据写入 hdfs 中，写入路径为 /hbase/table/region/sequenceid.temp，然后针对某一 Region 回放日志，将该 Region 对应的所有所有文件按照sequenceid由小到大依次进行回放即可。

Distributed Log Splitting 方式可以很大程度上加快故障恢复的进程，通常可以将故障恢复时间降低到分钟级别，但是这种方式会产生很多日志小文件，产生的文件数将是 M*N(其中 M 是待切分的总 HLog 数量，N 是 RegionServer 上的 Region 个数)。

### Distruted Log Replay

Distributed Log Replay(DLR) 方案相比于 DLS 方案有一些改动，Region 重新分配打开之后状态设置为 recovering，在此状态下的 Region 可以对外提供给写服务，不能提供读服务，而且不能执行 split, merge 等操作。

DLR 方案在分解 HLog 为 Region buffer 之后并没有写入小文件，而是直接执行回放，这样可以大大减少小文件的读写 IO 消耗。DLR 方案需要在配置中通过设置参数 hbase.master.distributed.log.replay=true 来开启，同时 HFile 的格式也要设置为 v3

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