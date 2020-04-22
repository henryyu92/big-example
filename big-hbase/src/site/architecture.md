## HBase 架构
#### Client
Client 通过查询 hbase:meta 表找到需要的 region 所在的 RegionServer 地址，然后直接和 RegionServer 通信从而读写数据而不需要经过 Master。Client 会缓存 hbase:meta 的数据，因此不必每次都需要查询，但是当 Region 重新分配或者 RegionServer 故障，客户端还是要重新获取 hbase:mata 表的数据。
#### HMaster
HMaster 是 Master Server 的实现，主要监控集群中的 RegionServer 实例。在集群中，Master 一般部署在 NameNode 上。Master 通过 Zookeeper 实现主从切换。

由于客户端直接根据 hbase:meta 表中的 RgionServer地址去直接和 RegionServer 对话，并且 hbase:meta 表并不在 Master 上，因此即使 Master 宕机，集群依然可以正常工作。但是由于 Master 负责RegionServer 的 failover 和 Region 的切分，所以当 Master 宕机时仍然需要尽量重启。

HMaster 在后台启动的线程：
- LoadBalancer: LoadBalancer 会定期检查 region 是否正常，如果不正常会移动 region 来达到负载均衡；可以通过 ```hbase.balancer.period``` 参数设置 loadbalancer 的周期，默认是 300000
- CatalogJanitor: CatalogJanitor 线程周期的去检查并清理 hbase:meta 表

HMaster 将管理操作及其运行状态(例如服务器崩溃、表创建和其他 DDL 操作)记录到自己的 WAL 文件中，WAL 存储在 MasterProcWALs 目录下。

#### HRegionServer
HRegionServer 是 RegionServer 的实现，负责对外提供服务并且管理 region；在分布式环境中，RegionServer 一般部署在 DataNode 上。

RegionServer 在后台启动的线程：
- CompactSplitThread: 负责检查 splits 并且执行 minor compaction
- MajorCompactionChecker: 负责检查 major compaction
- MemStoreFlusher: 周期性的将 MemStore 中的数据 flush 到 StoreFile 中
- LogRoller: 周期性的检查 RegionServer 的 WAL

#### HRegion
Region 是 Table 的可用性和分布式的最基本元素，Region 由每个列族的 Store 组成；通常 Habse 每个服务器上运行这比较少的 Region（20-200），但每个 Region 保存比较大的数据(5-20Gb)

#### HStore
Store 拥有一个 MemStore 和 0 个或多个 StoreFile(HFile)，Store 对应给定 Region 中表的列族
#### MemStore
MemStore 在内存中存储对 Store 的修改，当有 flush 请求时 MemStore 将数据的修改移动到 snapshot 然后清除。HBase 提供新的 MemStore 和备用的 snapshot 供编辑，直到 flusher 通知 flush 成功。当 flush 发生的时候属于同一 Region 的 MemStore 都将会 flush
#### StoreFile
#### HFile
### HBase 读写流程
### HBase 实现
#### Block Cache
##### LruBlockCache
##### Off-heap Block Cache

#### WAL
Write Ahead Log(WAL)将所有对 HBase 中数据的更改记录到文件中。通常不需要 WAL，因为数据已经从 MemStore 移动到 StoreFile 上了，但是如果在 MemeStore 数据刷到 StoreFile 上之前 RegionServer 不可用，则 WAL 保证数据的更改能够重放。

通常一个 RegionServer 只有一个 WAL 实例，但是携带 hbase:meta 的 RegionServer 例外，meta 表有自己专用的 WAL。RegionServer 在将 Put 和 Delete 操作记录在 MemStore 之前会先记录在 WAL。

WAL 存放在 HDFS 的 /hbase/WALs 目录的每个 Region 子目录下。
##### WAL 分割
一个 RegionServer 上有多个 Region，所有的 Region 共享相同的 WAL 文件；WAL 文件中的每个 edit 保存着相关 Region 的信息，当一个 Region 是 opened 的时候，对应的 WAL edit 就需要重放，因此 WAL 文件中的 edit 必须按照 Region 分组以便可以重放特定的集合以重新生成特定 Region 中的数据；按照 Region 对 WAL edit 进行分组的过程称为日志分割。如果 RegionServer 出现故障这是恢复数据的关键过程。

日志拆分在集群启动期间由 HMaster 完成或者在 Region 服务器关闭的时候由 ServerShutdownHandler 完成。为了保证一致性，受影响的 Region 在数据恢复之前不可用；在指定 Region 再次可用之前需要恢复和重放所有的 WAL edit，因此在进程完成之前受日志拆分影响的 Region 将不可用。

日志拆分的过程：
- ```/hbase/WALs/<host>,<port>,<startcode>``` 目录重命名

  重命名目录很重要，因为即使 HMaster 认为它已经关闭，RegionServer 仍可能正在启动并接受请求。如果 RegionServer 没有立即响应并且没有和 ZooKeeper 保持会话心跳，则 HMaster 可能会将其解释为 RegionServer 故障。重命名日志目录可确保现有有效的 WAL 文件被意外写入
  新的目录名称格式如下：
  ```
  /hbase/WALs/<host>,<port>,<startcode>-splitting
  ```
- 分裂每个日志，一次一个
  
  日志分割器一次读取日志文件的一个 edit 到与之对应的缓冲区中，同时分割器启动了几个写线程把缓冲区中的 edit 内容写到一个临时的 edit 文件，命名方式为：
  ```
  /hbase/<table_name>/<region_id>/recoverd.edits/.temp
  ```
  此文件用于存储该 Region 中 WAL 日志中的所有 edit，日志拆分完成之后 .temp 文件将重命名为写入该文件的第一个日志的 ID。
  
  确定是否所有的 edit 已经写入可以将序列 ID 与写入 HFile 的最后一个 edit 进行比较，如果最后一个 edit 大于或等于文件名中包含的序列 ID，则很明显从 edit 文件的写已经完成。
#### MemStore Flush
#### Region 合并
MemStore 的 flush 操作会逐步增加磁盘上的文件数目，合并(compaction)进程会将它们合并成规模更少但是更大的文件。合并有两种类型：Minor 合并和 Major 合并。

Minor 合并负责将一些小文件合并成更大的文件，合并的最小文件数由 ```hbase.hstore.compaction.min``` 属性设置，默认值为 3，同时该值必须大于等于 2。如果设置的更大一些则可以延迟 Minor 合并的发生，但同时在合并时需要更多的资源和更长的时间。Minor 合并的最大文件数由 ```hbase.store.compaction.max``` 参数设置，默认为 10。
可以通过设置 ```hbase.hstore.cmpaction.min.size``` 和 ```hbase.hstore.compaction.max.size``` 设置参与合并的文件的大小，在达到单次 compaction 允许的最大文件数之前小于最小阈值的文件也会参与 compaction 但是大于最大阈值的文件不会参与。

Major 合并将所有的文件合成一个，这个过程是通过执行合并检查自动确定的。当 MemStore 被 flush 到磁盘、执行 compaction 或者 major_compaction 命令、调用合并相关 API都会触发检查。

#### Region 分裂
Region 的分裂是在 RegionServer 上独立运行的，Master 不会参与。当一个 Region 内的存储文件大于 ```hbase.hregion.max.filesize``` 时，该 Region 就需要分裂为两个