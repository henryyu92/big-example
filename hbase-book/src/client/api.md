## API

HBase 提供了读写操作的 API，

```java
// 获取配置
Configuration conf = HBaseConfiguration.create();
// 建立链接，Connection 是线程安全的
Connnection conn = ConnectionFactory.createConnection(conf);
// Table 是线程不安全的
Table table = conn.getTable(TableName.valueOf("table-name");
// 构造请求
Get get = new Get("rowkey".getBytes());
Result result = table.get(get);
Cell cell = result.current();
System.out.println(new String(cell.getValueArray()));
```

HBase 默认会加载 `hbase-default.xml` 和 `hbase-site.xml` 文件中的配置，然后根据配置创建 ConnectionFactory

### GET

从 HBase 中查询数据是通过  `Get` 操作完成，默认情况下 `Get` 获取的是最新版本的数据，可以通过设置版本参数：

- `setTimeRange`：设置返回指定范围内的版本
- `readVersions`：获取指定版本的数据
- `readAllVersions`：获取所有版本的数据

```JAVA
Get get = new Get("rowkey".getBytes());
get.setAllVersions();
Result result = table.get(get);
```



### PUT

`Put` 操作将数据写入到 HBase 中，每次写操作会生成新版本的 Cell。



每次 Put 操作都会创建一个新版本的 Cell，默认情况下系统使用 ```currentTimeMillis```，可以在 Put 的时候指定版本，但是系统使用时间戳作为版本为了计算 TTL，因此最好不要自行设置版本。

服务端先写 WAL 然后写入 MemStore，默认每次写入都需要执行一次 RPC 和磁盘持久化操作，写入吞吐量受限于网络带宽以及 flush 的速度，但是由于每次写操作都能持久化到磁盘，因此不会有数据丢失

批量数据写入 API，客户端先缓存 put 当数量到达阈值后发起 RPC 写入请求，服务端一次性写入 WAL 和 MemStore。批量写入减少了 RPC 以及 flush 带来的开销，但是批量写入会由于 put 写往不同的 RegionServer 时不能保证数据写入的原子性，即可能出现部分写成功部分写失败，失败的部分需要重试

```java

```

### DELETE

HBase 的 Delete 操作不会立马修改数据，因此是通过创建名为“墓碑”的标记在主合并的时候连同数据一起被清除。

```JAVA

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



- `CompareOperator.LESS`
- `CompareOperator.LESS_OR_EQUAL`
- `CompareOperator.EQUAL`
- `CompareOperator.NOT_EQUAL`
- `CompareOperator.GREATER_OR_EQUAL`
- `CompareOperator.GREATER`
- `CompareOperator.NO_OP`

比较器

- `BinaryComparator`：按字节索引顺序比较指定字节数组
- `BinaryPrefixComparator`：比较前缀
- `NullComparator`：判断给定的是否为空
- `BitComparator`：按位比较
- `RegexStringComparator`：正则的比较器，仅支持 EQUAL 和非 EQUAL
- `SubstringComparator`：检测一个字符串是否包含于 value 中，不区分大小写

```java

```

- `SingleColumnValueFilter`
- `SingleColumnVlaueExcludeFilter`
- `FamilyFilter`：FamilyFilter 用于过滤列族，但通常会在使用 Scan 过程中通过设定扫描的列族来实现，而不是直接使用 FamilyFilter 实现
- `QualifierFilter`
- `ColumnPrefixFilter`：ColumnPrefixFilter 用于列限定符的前缀过滤，即过滤包含某个前缀的所有列名

```java

```

HBase 提供了多种 Filter，在使用 Filter 的过程中也需要注意：

- ```PrefixFilter```：过滤 rowkey 为指定前缀的数据，但是即使指定了前缀，Scan 也会从最开始的 rowkey 开始扫描从而会扫描大量的无效行，建议在使用 PrefixFilter 时指定 startRow 参数尽量过滤掉无用的数据扫描，或者将 PrefixFilter 转换成等价的 Scan
- ```PageFilter```：用于分页的 Filter，但是由于 HBase 中的 Filter 状态全部都是 Region 内有效的，Region 切换时其内部计数器会被清 0，因此可能导致扫描的数据跨 Region 导致返回数据量超过设定的页数量。使用 Scan 的 setLimit 方法可以实现分页功能
- ```SingleColumnValueFilter```：用于根据列过滤数据，SingleColumnValue 必须遍历一行数据中的每一个 cell，因而不能和其他 Filter 组合成 FilterList

### Admin

Admin 操作提供了对 HBase 的管理，包括命名空间和表的管理，Compaction 的执行，Region 的迁移等。

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

