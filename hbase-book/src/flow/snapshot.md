## 快照

Snapshot 是 HBase 的一个核心功能，使用在线 Snapshot 备份可以实现增量备份和数据迁移。HBase 提供了 4 个在线 Snapshot 备份与恢复的工具：
- snapshot：为表生成一个快照，但并不涉及数据移动，可以在线完成 ```snapshot 'sourceTable', 'snapshotName'```
- restore_snapshot：恢复指定快照，恢复过程会替代原有数据，将表还原到快照点，快照点之后的所有更新将会丢失 ```resotre_snapshot 'snapshotName'
- clone_snapshot：根据快照恢复出一个新表，恢复过程不涉及数据移动，可以在秒级完成 ```clone_snapshot 'snapshotName', 'tableName'```
- ExportSnapshot：可以将一个集群的快照数据迁移到另一个集群，ExportSnapshot 是 HDFS 层面的操作，需要用 MapReduce 进行数据的并行迁移，因此需要两个集群支持 MapReduce。Master 和 RegionServer 不参与迁移过程，因此不会带来额外的内存开销以及 GC 开销。ExportSnapshot 针对 DataNode 需要额外的带宽以及 IO 负责问题设置了 bandwidth 来限制带宽的使用 ```hbase.org.apache.hadoop.hbase.snapshot.ExportSnapshot -snapshot MySnapshot -copy-from hdfs://srv2.8082/hbase -copy-to hdfs://srv1:50070/hbase -mappers 16 -bandwidth 1024```

Snapshot 机制不会拷贝数据，而是类似于原始数据的一份指针，HBase 的 snapshot 只需要为当前表的所有文件分别新建一个引用，对于其他新写入的数据，重新创建一个新文件写入即可。snapshot 流程主要涉及两个步骤：
- 将 MemStore 中的缓存数据 flush 到文件中
- 为所有 HFile 文件分别新建引用指针，这些指针元数据就是 snapshot

HBase 为指定表执行 snapshot 操作时，实际上真正执行 snapshot 的是对应表的所有 Region，HBase 使用二阶段提交(Two-Phase Commit, 2PC)协议来保证这些 Region 在多个不同的 RegionServer 上要么都完成，要么都失败。2PC 一般由一个协调者和多个参与者组成，整个事务提交过程如下：
- prepare 阶段协调者会向所有参与者发送 prepare 命令
- 所有参与者接收到命令，获取相应资源，执行 prepare 操作确认可以执行成功，一般情况下核心工作都是在 prepare 操作中完成
- 返回给协调者 prepared 应答
- 协调者接收到所有参与者返回的 prepared 应答，表示所有参与者都已经准备好提交，然后在本地持久化 committed 状态
- 持久化完成之后进入 commit 阶段，协调者会向所有参与者发送 commit 命令
- 参与者接收到 commit 命令，执行 commit 操作并释放资源，通常 commit 操作都比较简单
- 返回给协调者

prepare 阶段：
- Master 在 ZK 创建一个 /acquired-snapshotname 节点，并在此节点上写入 Snapshot 相关信息
- 所有 RegionServer 检测到这个节点，根据这个节点的 snapshot 信息查看当前 RegionServer 上是否有目标表，如果不存在则忽略，如果存在则遍历目标表中的所有 Region，针对每个 Region 分别执行 snapshot 操作。此时 snapshot 操作的结果并没有写入最终文件夹，而是写入临时文件夹
- RegionServer 执行完成之后会在 /acquired-snapshotname 节点下新建一个子节点 /acquired-snapshotname/nodex，表示 nodex 节点完成了该 RegionServer 上所有相关 Region 的 snapshot 准备工作

commit 阶段：
- 一旦所有 RegionServer 都完成了 snapshot 的 prepare 工作，即都在 /acquired-snapshotname 节点下新建一个子节点 /acquired-snapshotname/nodex 节点，Master 就认为 Snapshot 的准备工作完全完成，Master 会创建一个新的节点 /reached-snapshotname，表示发送一个 commit 命令给参与的 RegionServer
- 所有 RegionServer 检测到 /reached-snapshotname 节点之后，执行 commit 操作，即将 prepare 阶段生成的结果从临时文件夹移动到最终文件夹即可
- 在 /reached-snapshotname 节点下创建子节点 /reached-snapshotname/nodex，表示节点 nodex 完成 snapshot 工作

abort 阶段：
- 如果在一定时间内 /acquired-snapshotname 没有满足条件，即还有 RegionServer 的准备工作没有完成，Master 认为 snapshot 的准备工作超时，Master 认为 snapshot 的准备工作超时，Master 会新建另一个新节点 /abort-snapshotname，所有 RegionServer 监听到这个命令之后会清理 snapshot 在临时文件夹中生成的结果

在整个 snapshot 过程中，Master 充当了协调者的角色，RegionServer 充当了参与者的角色，Master 和 RegionServer 之间的通信通过 ZK 来完成，同时事务状态也记录在 ZK 的节点上，Master 在高可用的情况下即使发生了切换也会根据 ZK 上的事务状态决定是否继续提交或者回滚

snapshot 在两阶段提交过程中 RegionServer 实现 snapshot 的过程分为三步：
- 将 MemStore 中数据 flush 到 HFile
- 将 region info 元数据(表名、startKey 和 endKey) 记录到 snapshot 文件中
- 将 Region 中所有的 HFile 文件名记录到 snapshot 文件中

Master 会在所有 Region 完成 snapshot 之后执行一个汇总操作，将所有 region snapshot manifest 汇总成一个单独的 manifest 存放在 HDFS 目录 /hbase/.hbase-snapshot/snapshotname/data.menifest，该目录下有三个文件，其中 .snapshotinfo 为 Snapshot 基本信息，包含待 snapshot 的表名称以及 snapshot 名；data.manifest 为 snapshot 执行后生成的元数据信息，即 snapshot 结果信息

clone_snapshot 过程分为六步：
- 预检查，确认当前目标表没有执行 snapshot 以及 restore 等操作，否则直接返回错误
- 在 tmp 文件夹下新建目标表目录并在表目录下新建 .tabledesc 文件，在该文件中写入表 schema 信息
- 新建 region 目录，根据 snapshot manifest 中的信息新建 Region 相关目录以及 HFile 文件
- 将表目录从 tmp 文件夹下移到 HBase Root Loacation
- 修改 hbase:meta 表，将克隆表的 Region 信息添加到 hbase:meta 表中，注意克隆表的 Region 名称和原数据表的 Region 名称并不相同
- 将这些 Region 通过 round-robin 方式均匀分配到整个集群中，并在 ZK 上将克隆表的状态设置为 enabled，正式对外提供服务

clone_snapshot 工具克隆表的过程并不涉及数据的移动，而是使用了 LinkFile 的文件指向了源文件，LinkFile 文件本身没有任何内容，它的所有核心信息都包含在它的文件名中，通过文件名可以定位到原始文件的具体路径

snapshot 实际上是一系列原始表的元数据，主要包括表 schema 信息、原始表的所有 Region 的 regioninfo 信息，如果原始表发生了 Compaction 导致 HFile 文件名发生了变化或者 Region 发生了分裂等，HBase 会在原始表发生 compaction 操作前将原始表数据复制到 archive 目录下再执行 compact，这样 snapshot 对应的元数据就不会失去意义，只不过原始数据不再存放于数据目录下，而是移动到了 archive 目录下
