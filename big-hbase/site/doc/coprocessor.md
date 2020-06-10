## Coprocessor

HBase 使用 Coprocessor 机制使用户可以将自己编写的程序运行在 RegionServer 上，从而在特定场景下大幅提升执行效率。在某些场景下需要把数据全部扫描出来在客户端进行计算，就会有如下问题：
- 大量数据传输可能会成为瓶颈，导致整个业务的执行效率受限于数据传输效率
- 客户端内存可能会因为无法存储如此大量的数据而 OOM
- 大量数据传输可能将集群带宽耗尽，严重影响集群中其他业务的正常读写

如果将客户端的计算代码迁移到 RegionServer 服务端执行，就能很好地避免上述问题。

HBase Coprocessor 分为两种：Observer 和 Endpoint


Observer Coprocessor 提供钩子使用户代码在特定事件发生之前或者之后得到执行，只需要重写对应的方法即可。HBase 提供了 4 中 Observer 接口：
- RegionObserver：主要监听 Region 相关事件
- WALObserver：主要监听 WAL 相关事件，比如 WAL 写入、滚动等
- MasterObserver：主要监听 Master 相关事件，比如建表、删表以及修改表结构等
- RegionServerObserver：主要监听 RegionServer 相关事件，比如 RegionServer 启动、关闭或者执行 Region 的合并等事件

Endpoint Coprocessor 允许将用户代码下推到数据层执行，可以使用 Endpoint Coprocessor 将计算逻辑下推到 RegionServer 执行，通过 Endpoint Coprocessor 可以自定义一个客户端与 RegionServer 通信的 RPC 调用协议，通过 RPC 调用执行部署在服务器的业务代码，Endpoint Coprocessor 执行必须由用户显示触发调用。

自定义的 Coprocessor 可以通过两种方式加载到 RegionServer：通过配置文件静态加载、动态加载

通过静态加载的方式将 Coprocessor 加载到集群需要执行 3 个步骤：
- 将 Coprocessor 配置到 hbase-site.xml 中， hbase.coprocessor.region.classes 配置 RegionObservers 和 Endpoint Coprocessor；hbase.coprocessor.wal.classes 配置 WALObservers；hbase.coprocessor.master.classes 配置 MasterObservers
- 将 Coprocessor 代码放到 HBase 的 classpath 下
- 重启 HBase 集群

静态加载 Coprocessor 需要重启集群，使用动态加载方式则不需要重启，动态加载有 3 中方式：

使用shell:
```shell
# disable 表
disable 'tablename'

# 修改表 schema
alter 'tablename', METHOD=>'table_att', 'Coprocessor'=>'hdfs://....coprocessor.jar|org.libs.hbase.Coprocessor.RegionObserverExample "|"'

# enable 表
enable 'tablename'
```
使用 HTableDescriptor 的 setValue 方法
```java
String path = "hdfs://path/of/coprocess.jar";
HTableDescriptor descriptor = new HTableDescriptor(tableName);
descriptor.setValue("COPROCESSOR$1", path+"|"+RegionObserverExample.class.getCanonicalName() + "|" + Coprocessor.PRIORITY_USER);
```
使用 HTableDescriptor 的 addCoprocessor 方法