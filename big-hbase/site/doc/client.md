## 客户端

HBase 本身是 Java 开发的，因此非 Java 语言的客户端需要先访问 ThriftServer，然后通过 ThriftServer 的 Java HBase 客户端来请求 HBase 集群。HBase 也支持 Shell 交互式客户端，其本质是用 JRuby 脚本调用 HBase Java 客户端来实现。

HBase 将 Region 定位功能设计在客户端上，因此 HBase 的客户端并不轻量级。

HBase 客户端运行需要 4 个步骤：
- 获取集群 Configuration 对象：HBase 客户端需要使用到 hbase-site.xml, core-site.xml, hdfs-site.xml 这 3 个配置文件，需要将这三个文件放入到 JVM 能加载的 classpath 下，然后通过 ```HBaseConfiguration.create()``` 加载到 Configuration 对象
- 根据 Configuration 初始化集群 Connection 对象：Connection 维护了客户端到 HBase 集群的连接。一个进程中只需要建立一个 Connection 即可，HBase 的 Connection 本质上是是由连接到集群上所有节点的 TCP 连接组成，客户端请求根据请求的数据分发到不同的物理节点。Connection 还缓存了访问的 Meta 信息，这样后续的大部分请求都可以通过缓存的 Meta 信息直接定位到对应的 RegionServer 上
- 通过 Connection 实例化 Table：Table 是一个轻量级的对象，实现了访问数据的 API 操作，请求执行完毕之后需要关闭 Table
- 执行 API 操作

### 定位 Meta 表

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

### Admin

Admin 提供了对 HBase 的管理，包括命名空间和表的管理，Compaction 的执行，Region 的迁移等。

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

### SCAN

HBase 客户端的 Scan 操作能够设置多个维度的属性，使得 Scan 操作比较复杂。Scan 操作在获取到 scanner 之后调用 next 方法获取数据时先到 cache 队列中拿数据，如果 cache 队列中数据不足则会发起一次 RPC 向服务端请求数据，客户端收到数据之后通过 scanResultCache 把这些数据内的多个 cell 进行重组，最终组成用户需要的结果放入 cache 中。整个 RPC 请求到重组数据放入 cache 的过程称为 loadCache 操作。

RegionServer 为了避免 RPC 请求耗尽资源，会对多个维度进行限制，一旦某个维度资源达到阈值，就马上把当前拿到的 cell 返回给客户端，这样客户端拿到的 result 可能不是一行完整的数据，因此需要和之前获取到的 cell 进行重组。Scan 过程中涉及的资源限制：
- ```caching```：每次 loadCache 操作最多放入到 cache 中的 rpc 请求结果数量
- ```batch```：RPC 请求结果中每行数据最多的 cell 个数
- ```allowPartial```：设置是否容忍部分数据，如果为 true 则不会重组而直接将返回 result 数据返回
- ```maxResultSize```：loadCache 时单次 RPC 操作获取的 result 的最大字节数

HBase 客户端到服务端通信过程中会由于多种原因需要重试，在发起 RPC 请求时有一些常见的超时参数设置：
- ```hbase.rpc.timeout```：单次 RPC 请求的超时时间，默认 60000 ms，超时后抛出 TimeoutException
- ```hbase.client.tries.number```：客户端单次 API 调用时最多容许发生 RPC 重试的次数，默认 35 次
- ```hbase.client.pause```：连续两次 RPC 重试之间的休眠，默认 100 ms，HBase 的休眠时间是按照随机退避算法计算的，因此休眠时间随着重试次数增加而增加
- ```hbase.client.operation.timeout```：客户端单次 API 调用的超时时间，默认值是 120000 ms，此时间包含 RPC 超时时间以及重试休眠时间

HBase 客户端提供了 CAS 接口，保证在高并发场景下读取与写入的原子性。这些 CAS 接口在 RegionServer 上是 Region 级别的，即在单个 Region 上是串行的，而在多个 Region 之间是并行的。

### Filter

HBase 在 Scan 的时候可以设置多个 Filter，使得大量无效数据可以在服务端内部过滤，相比直接返回全表数据到客户端然后在客户端过滤要高效的多。

HBase 提供了多种 Filter 根据列族、列、版本等条件对数据进行过滤，带有过滤条件的 RPC 请求会将过滤器分发到各个 RegionServer 上以减少网络传输压力和客户端压力。完成一个过滤操作需要两个参数：抽象的操作符 和 具体的比较器，HBase 提供了大量的操作符和比较器。

操作符 | 含义
:-: | :-: |
CompareOperator.LESS | 小于
CompareOperator.LESS_OR_EQUAL | 小于等于
CompareOperator.EQUAL | 等于
CompareOperator.NOT_EQUAL | 不等于
CompareOperator.GREATER_OR_EQUAL | 大于等于
CompareOperator.GREATER | 大于
CompareOperator.NO_OP | 排除所有

比较器 | 含义
:-: | :-: |
BinaryComparator | 按字节索引顺序比较指定字节数组
BinaryPrefixComparator | 比较
NullComparator | 判断给定的是否为空
BitComparator | 按位比较
RegexStringComparator | 正则的比较器，仅支持 EQUAL 和非 EQUAL
SubstringComparator | 子串是否出现在 value 中

#### SingleColumnValueFilter
#### SingleColumnVlaueExcludeFilter
#### FamilyFilter
FamilyFilter 用于过滤列族，但通常会在使用 Scan 过程中通过设定扫描的列族来实现，而不是直接使用 FamilyFilter 实现。
#### QualifierFilter
#### ColumnPrefixFilter
ColumnPrefixFilter 用于列限定符的前缀过滤，即过滤包含某个前缀的所有列名。

HBase 提供了多种 Filter，在使用 Filter 的过程中也需要注意：
- ```PrefixFilter```：过滤 rowkey 为指定前缀的数据，但是即使指定了前缀，Scan 也会从最开始的 rowkey 开始扫描从而会扫描大量的无效行，建议在使用 PrefixFilter 时指定 startRow 参数尽量过滤掉无用的数据扫描，或者将 PrefixFilter 转换成等价的 Scan
- ```PageFilter```：用于分页的 Filter，但是由于 HBase 中的 Filter 状态全部都是 Region 内有效的，Region 切换时其内部计数器会被清 0，因此可能导致扫描的数据跨 Region 导致返回数据量超过设定的页数量。使用 Scan 的 setLimit 方法可以实现分页功能
- ```SingleColumnValueFilter```：用于根据列过滤数据，SingleColumnValue 必须遍历一行数据中的每一个 cell，因而不能和其他 Filter 组合成 FilterList


#### RegexStringComparator
RegexStringComparator 支持正则表达式的值比较。
```
// 正则表达式和 Java 正则表达式相同
RegexStringComparator comparator = new RegexStringComparator("regex")
SingleColumnValueFilter filter = new SingleValueFilter(cf, column, CompareOp.EQUAL, comparator);
scan.setFilter(filter);
```
#### SubstringComparator
SubstringComparator 用于检测一个字符串是否包含于值中，不区分大小写。
```
SubstringComparator comparator = new SbustringComparator("sub");
SingleColumnValueFilter filter = new SingleValueFilter(cf, column, CompareOp.EQUAL, comparator);
scan.setFilter(filter);
```
#### BinaryPrefixComparator
BinaryPrefixComparator 是前缀二进制比较器，只比较前缀是否相同。
```

```
#### BinaryComparator
BinaryComparator 是二进制比较器，用于按照字典序比较 Byte 数据值。
```
```


### Put

HBase 是一种对写操作友好的系统，为了适应不同数据量的写场景，HBase 提供了 3 种数据写入的 API：
- ```put(Put)```：单行数据写入 API，服务端先写 WAL 然后写入 MemStore。默认每次写入都需要执行一次 RPC 和磁盘持久化操作，写入吞吐量受限于网络带宽以及 flush 的速度，但是由于每次写操作都能持久化到磁盘，因此不会有数据丢失
- ```put(List<Put>)```：批量数据写入 API，客户端先缓存 put 当数量到达阈值后发起 RPC 写入请求，服务端一次性写入 WAL 和 MemStore。批量写入减少了 RPC 以及 flush 带来的开销，但是批量写入会由于 put 写往不同的 RegionServer 时不能保证数据写入的原子性，即可能出现部分写成功部分写失败，失败的部分需要重试
- ```bulkLoad```：HBase 提供了将数据生成 HFile 然后直接加载到对应的 Region 下的 Column Family 内，在生成 HFile 时服务端不会有任何 RPC 调用，只有在 load HFile 时会调用 RPC。bulkLoad 是一种完全离线的快速批量写入方案，不会对集群产生巨大压力

每次 Put 操作都会创建一个新版本的 Cell，默认情况下系统使用 ```currentTimeMillis```，可以在 Put 的时候指定版本，但是系统使用时间戳作为版本为了计算 TTL，因此最好不要自行设置版本。

```java
```

### Get

默认在 Get 操作没有显式指定版本的时候的到的是最新版本的数据，可以在 Get 的时候设置版本相关参数：
- Get.setMaxVersion() - 设定返回多个版本的数据
- Get.setTimeRange() - 设置返回指定版本的数据

### Delete

HBase 的 Delete 操作不会立马修改数据，因此是通过创建名为“墓碑”的标记在主合并的时候连同数据一起被清除。



### 问题排查

由于 HBase 客户端比较重量级，需要处理比较复杂的操作，这种复杂性有时会使得客户端出现一些异常。

