## Meta

HBase 中表的数据是由多个 Region 构成，这些 Region 分布在整个集群的 RegionServer 上，因此客户端在操作数据时首先需要确定数据所在的 RegionServer，然后才能到对应的 RegionServer 上操作数据。

HBase 设计了内部表 ```hbase:meta``` 表专门用来存放整个集群所有的 Region 信息，**HBase 保证 hbase:meta 表始终只有一个 Region，这样对 meta 表的操作的原子性**。

```hbase:meta``` 表只有 ```info``` 这个列簇，每行数据都表示一个 Region 信息，其中 rowkey 是由 `table,start_row,region_id,encode_name`  组成

![meta]()

列簇中包含 4 列：

- ```info:regioninfo```：主要存储 EncodedName, RegionName, StartRow, StopRow
- ```info:seqnumDuringOpen```：主要存储 Region 打开时的 sequenceId
- ```info:server```：主要存储 Region 对应的 RegionServer
- ```info:serverstartcode```：主要存储 Region 对应的 RegionServer 的启动 TimeStamp

`hbase:meta` 存放着系统中所有的 region 的信息，以 key-value 的形式存放：

- key 以 ([table],[region start key],[region id]) 表示的 Region，如果 start key 为空的则说明是第一个 Region，如果 start key 和 end key 都为空则说明只有一个 Region
- value 保存 region 的信息，其中 info:regioninfo 是当前 Regsion 的 HRegionInfo 实例的序列化；info:server 是当前 Region 所在的 RegionServer 地址，以 server:port 表示；info:serverstartcode 是当前 Region 所在的 RegionServer 进程的启动时间

当 HBase 表正在分裂时，info:splitA 和 info:splitB 分别代表 region 的两个孩子，这些信息也会序列化到 HRegionInfo 中，当 region 分裂完成就会删除。

`hbase-metadata` 表存储在 RegionServer 上，对应 RegionServer 的信息存储在 ZooKeeper 上。

客户端会缓存 Meta 信息到 `MetaCache`，每次在查询前会根据查询的 rowkey 从 MetaCache 中查找对应的 Region 信息，此时会出现三种情况：

- Region 信息为空，说明 MetaCache 中没有 rowkey 所在的 Region 信息，此时需要先到 ZooKeeper 的 ```/hbase/meta-region-server``` ZNode 上获取 meta:info 这个表所在的 RegionServer，之后指定的 RegionServer 读取 meta 表的数据并缓存到 MetaCache 中。
- Region 信息不为空，但是调用 RPC 请求对应的 RegionServer 后发现 Region 并不在这个 RegionServer 上。这种情况是 MetaCache 上的信息过期了，这时需要重新读取 hbase:meta 表中的数据并更新 MetaCache，这种情况一般发生在 Region 发生迁移时
- Region 信息不为空，RPC 请求正常，大部分请求是这种正确的情况

因为有 MetaCache 的设计，客户端的请求不会每次都定位 Region，这样就避免了 hbase:meta 表承受过大的压力。