## 配置

HBase 依赖于 ZooKeeper 和 HDFS，通过配置 ZooKeeper 和 HDFS 的参数可以使 HBase 达到更佳的性能。

### ZooKeeper

HBase 使用 ZooKeeper 实现了 Master 的高可用、RegionServer 宕机异常检测、分布式锁等一系列功能。HBase 集群的部署依赖于 ZooKeeper，在 HBase 的配置文件 conf/hbase-site.xml 中需要配置 ZooKeeper 相关的属性：
```xml
<!-- 配置 ZK 集群 host，必需 -->
<property>
    <name>hbase.zookeeper.quorum</name>
    <value>localhost</value>
</property>
<!-- ZK 客户端端口，非必需 -->
<property>
    <name>hbase.zookeeper.property.clientPort</name>
    <value>2181</value>
</property>
```
ZooKeeper 中存储了 HBase 的元数据信息，这些数据通过在 ZooKeeper 的 /hbase 节点下创建子节点保存：
- ```meta-region-server```： 存储 HBase 集群 hbase:meta 元数据表所在的 RegionServer 访问地址。客户端读写数据首先会从此节点读取 hbase:meta 元数据的访问地址，将部分元数据加载到本地，根据元数据进行数据路由
- ```master/backup-master```：存储 HBase 集群中 Master 和 backupMaster 节点的信息
- ```table```：集群中所有表的信息
- ```region-in-transition```：记录 Region 迁移过程中的状态变更。Region 的迁移需要先执行 unassign 操作将此 Region 从 open 状态变为 offline 状态(中间涉及 pending_close, closing, closed 等过渡状态)，然后在目标 RegionServer 上执行 assign 操作使 Region 的状态从 offline 变为 open，在 Region 的整个迁移过程中 RegionServer 将 Region 的状态保存到 ZooKeeper 的 ```/hbase/region-in-transition``` 节点中。Master 监听 ZooKeeper 对应的节点，当 Region 状态发生变更后能立马获得通知，然后更新 Region 在 hbase:meta 中的状态和内存中的状态
- ```table-lock```：HBase 使用 ZooKeeper 实现分布式锁。HBase 支持单行事务，对表的 DDL 操作之前需要先获取表锁，防止多个 DDL 操作之间发生冲突，由于 Region 分布在多个 RegionServer 上，因此表锁需要使用分布式锁
- ```onlline-snapshot```：实现在线 snapshot 操作。Master 通过 online-snapshot 节点通知监听的 RegionServer 对目标 Region 执行 snapshot 操作
- ```replication```：实现 HBase 复制功能
- ```splitWAL/recovering-regions```：用于 HBase 故障恢复
- ```rs```：存储集群中所有运行的 RegionServer
- ```hbaseid```：
- ```namespace```：
- ```balancer```：

### HDFS

HBase 的数据文件都存放在 HDFS 上，通过 HDFS 的可扩展性以及多副本可靠性保证 HBase 的可扩展性和高可靠性。

- HBase 本身并不存储文件，它只规定文件格式以及文件内容，实际文件存储由 HDFS 实现
- HBase 不提供机制保证存储数据的高可靠，数据的高可靠性由 HDFS 的多副本机制保证
- HBase-HDFS 体系是典型的计算存储分离架构，可以方便的使用其他存储代替 HDFS 作为 HBase 的存储方案，也可以使计算资源和存储资源独立扩容缩容

HBase 基于 HDFS 存储的体系是典型的计算和存储分离的架构，这种耦合使得可以独立的扩容存储或者计算而不会相互影响。

HBase 的数据默认存储在 HDFS 的 ```/hbase```目录下：
- ```.hbase-snapshot```：snapshot 文件存储目录，执行 snapshot 操作后相关的 snapshot 元数据文件存储在该目录
- ```.tmp```：临时文件目录，主要用于 HBase 表的创建和删除操作。表创建的时候首先会在 tmp 目录下执行，执行成功后再将 tmp 目录下的表信息移动到实际表目录下。表删除操作会将表目录移动到 tmp 目录下，一定时间过后再将 tmp 目录下的文件真正删除
- ```MasterProcWALs```：存储 Master Procedure 过程中的 WAL 文件。Master Procedure 功能主要主要用于可恢复的分布式 DDL 操作，Master Procedure 使用 WAL 记录 DDL 执行的中间状态，在异常发生之后可以通过 WAL 回放明确定位到中间状态点，继续执行后续操作以保证整个 DDL 操作的完整性
- ```WALs```：存储集群中所有 RegionServer 的 HLog 文件
- ```achive```：文件归档目录，主要在以下场景使用：
  - 所有对 HFile 文件的删除操作都会将待删除文件临时存放在该目录
  - 进行 Snapshot 或者升级时使用到的归档目录
  - Compaction 删除 HFile 的时候，会把就得 HFile 移动到该目录
- ```corrupt```：存储损坏的 HLog 文件或者 HFile 文件
- ```data```：存储集群中所有 Region 的 HFile 文件，HFile 文件位于该目录下对应的```<namespace>/<table>/<region>/<family>/<file>``` 中

除了 HFile 文件外，data 目录下还存储了一些重要的目录和子文件：
- ```.tabledesc```：表描述文件，记录对应表的基本 schema 信息
- ```.tmp```：表临时目录，主要用来存储 Flush 和 Comapction 过程中的中间结果
- ```.regioninfo```：Region 描述文件
- ```recovered.edits```：存储故障恢复时该 Region 需要回放的 WAL 日志。RegionServer 宕机之后，该节点上还没有来得及 flush 到磁盘的数据需要通过 WAL 回放恢复，WAL 文件首先需要按照 Region 进行切分，每个 Region 拥有对应的 WAL 数据片段，回放时只需要回放自己的 WAL 数据片段即可
- ```hbase.id```：集群启动初始化的时候，创建的集群唯一 id
- ```hbase.version```：HBase 软件版本，代码静态版本
- ```oldWALs```：WAL 归档目录。一旦一个 WAL 文件中记录的所有 K-V 数据确认已经从 MemStore 持久化到 HFile，那么该 WAL 文件就会被移除到该目录