## Meta

Client 通过查询 hbase:meta 表找到需要的 region 所在的 RegionServer 地址，然后直接和 RegionServer 通信从而读写数据而不需要经过 Master。Client 会缓存 hbase:meta 的数据，因此不必每次都需要查询，但是当 Region 重新分配或者 RegionServer 故障，客户端还是要重新获取 hbase:mata 表的数据。

### 定位 Meta

HBase 中表的数据是由多个 Region 构成，这些 Region 分布在整个集群的 RegionServer 上，因此客户端在操作数据时首先需要确定数据所在的 RegionServer，然后才能到对应的 RegionServer 上操作数据。

HBase 设计了内部表 ```hbase:meta``` 表专门用来存放整个集群所有的 Region 信息。```hbase:meta``` 表只有 ```info``` 这个列簇，**HBase 保证 hbase:meta 表始终只有一个 Region，这样对 meta 表的操作的原子性。**表中的每行数据都表示一个 Region 信息，其中 rowkey 是由 ```表名 + Region 起始 rowkey + Region 创建时间 + 前面三个字段的 MD5 Hex 值，即 <TableName>_<StartRow>_<Timestamp>_<EncodedName>```。列簇中包含 4 列：

- ```info:regioninfo```：主要存储 EncodedName, RegionName, StartRow, StopRow
- ```info:seqnumDuringOpen```：主要存储 Region 打开时的 sequenceId
- ```info:server```：主要存储 Region 对应的 RegionServer
- ```info:serverstartcode```：主要存储 Region 对应的 RegionServer 的启动 TimeStamp

HBase 客户端缓存 ```hbase:meta``` 信息到 MetaCache，客户端在根据 rowkey 查询数据时首先会到 MetaCache 中查找 rowkey 对应的 Region 信息，此时会出现三种情况：

- Region 信息为空，说明 MetaCache 中没有 rowkey 所在的 Region 信息，此时需要先到 ZooKeeper 的 ```/hbase/meta-region-server``` ZNode 上获取 meta:info 这个表所在的 RegionServer，之后指定的 RegionServer 读取 meta 表的数据并缓存到 MetaCache 中。
- Region 信息不为空，但是调用 RPC 请求对应的 RegionServer 后发现 Region 并不在这个 RegionServer 上。这种情况是 MetaCache 上的信息过期了，这时需要重新读取 hbase:meta 表中的数据并更新 MetaCache，这种情况一般发生在 Region 发生迁移时
- Region 信息不为空，RPC 请求正常，大部分请求是这种正确的情况

因为有 MetaCache 的设计，客户端的请求不会每次都定位 Region，这样就避免了 hbase:meta 表承受过大的压力。