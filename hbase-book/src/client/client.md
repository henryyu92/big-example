## 客户端


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