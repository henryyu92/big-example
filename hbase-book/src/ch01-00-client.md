# 客户端


HBase 客户端获取数据前需要定位到数据对应 Region 所在的 RegionServer，这使得客户端的操作并不轻量。

HBase 客户端使用 Java 开发，通过 ThriftServer 可以支持其他语言，除此之外 HBase 还提供了交互式的 Shell 客户端，其本质是使用 JRuby 脚本调用 Java 客户端实现。

#### Shell

HBase 客户端使用 Java 开发，通过 ThriftServer 可以支持其他语言，除此之外 HBase 还提供了交互式的 Shell 客户端，其本质是使用 JRuby 脚本调用 Java 客户端实现。

```sh
# 启动 HBase Shell
./hbase shell
```


### 客户端实现

HBase 客户端运行需要 4 个步骤：

- 获取集群 Configuration 对象：HBase 客户端需要使用到 hbase-site.xml, core-site.xml, hdfs-site.xml 这 3 个配置文件，需要将这三个文件放入到 JVM 能加载的 classpath 下，然后通过 ```HBaseConfiguration.create()``` 加载到 Configuration 对象
- 根据 Configuration 初始化集群 Connection 对象：Connection 维护了客户端到 HBase 集群的连接。一个进程中只需要建立一个 Connection 即可，HBase 的 Connection 本质上是是由连接到集群上所有节点的 TCP 连接组成，客户端请求根据请求的数据分发到不同的物理节点。Connection 还缓存了访问的 Meta 信息，这样后续的大部分请求都可以通过缓存的 Meta 信息直接定位到对应的 RegionServer 上
- 通过 Connection 实例化 Table：Table 是一个轻量级的对象，实现了访问数据的 API 操作，请求执行完毕之后需要关闭 Table
- 执行 API 操作

#### 定位 Meta 表

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






### 问题排查

由于 HBase 客户端比较重量级，需要处理比较复杂的操作，这种复杂性有时会使得客户端出现一些异常。