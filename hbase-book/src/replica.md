## 复制
复制功能为 HBase 跨集群数据同步提供了支撑。HBase 客户端创建一个 Peer（一条主集群到备份集群的复制链路），包含 PeerId, 备份集群的 ZK 地址、是否开启数据同步，以及需要同步的 namespace, table, column family 等，HBase 客户端创建 Peer 的流程如下：
- 将创建 Peer 的请求发送到 Master
- Master 内部实现了一个名为 Procedure 的框架，对于一个 HBase 的管理操作会拆分成多步，每步执行完成之后会将状态信息持久化到 HDFS 上，然后继续执行下一步操作，这样在任何一步出现异常就可以在这一步重新执行而不需要全部重新执行。对于创建 Peer 来说，Procedure 会为该 Peer 创建相关的 ZNode，并将复制相关的元数据保存在 ZK 上
- Master 的 Procedure 会向每一个 RegionServer 发送创建 Peer 的请求，直到所有的 RegionServer 都成功创建 Peer，否则会重试
- Master 返回给 HBase 客户端

在创建完 Peer 后，真正负责数据同步的是 RegionServer 的 ReplicationSource 线程，该线程复制数据的流程如下：
- 在创建 Peer 时，每个 RegionServer 会创建一个 ReplicationSource 线程，ReplicationSource 首先把当前写入的 HLog 都保存在复制队列中，然后再 RegionServer 上注册了一个 Listener，用来监听 HLog Roll 操作，如果 RegionServer 做了 HLog roll 操作，那么 ReplicationSource 收到这个操作后会把这个 HLog 分到对应的 walGroupQueue 里面，同时把 HLog 文件名持久化到 ZK 上，这样重启后还可以接着复制未复制完成的 HLog
- 每个 WalGroupQueue 后端有一个 ReplicationSourceWALReader 的线程，这个线程不断的从 Queue 中取出一个 HLog，然后把 HLog 中的 Entry 逐个读取出来，放到一个名为 entryBatchQueue 的队列中
- entryBatchQueue 后端有一个名为 ReplicationSourceShipper 的线程，不断从 Queue 中读取 Log Entry，交给 Peer 的 ReplicationEndpoint。ReplicationEndpoint 把这些 Entry 打包成一个 replicationWALEntry 操作，通过 RPC 发送到 Peer 集群的某个 RegionServer 上。对应 Peer 集群的 RegionServer 把 replicateWALEntry 解析成若干个 Batch 操作，并调用 batch 接口执行。待 RPC 调用成功后，ReplicationSourceShipper 会更新最近一次成功复制的 HLog Position 到 ZK，以便 RegionServer 重启后，下次能找到最新的 Position 开始复制

一个 Peer 可能存在多个 walGroupQueue，因为现在 RegionServer 为了实现更高的吞吐量允许同时写多个 WAL，同时写的多个 WAL 属于独立的 Group，所以在一个 Peer 内为每个 Group 设置一个 walGroupQueue。一种常见的场景是，每个业务设置一个独立的 namespace，然后每个 namespace 写自己独立的 WAL，不同的 WAL Group 通过不同的复制线程去推，这样如果某个业务复制阻塞了并不会影响其他的业务，因为不同的 namespace 产生的 HLog 会分到不同的 walGroupQueue。

复制的过程中有两个重要的信息存放在 ZK 上：
- Peer 相关信息，/hbase/replication/peers 目录下存放了 Peer 相关信息
- Peer 和 HLog 推送关系，/hbase/replication/rs 目录下记录了 Peer 的 HLog，以及 HLog 推送的 Postion

### 串行复制
非串行复制的情况下，如果 Region 在复制的时候发生了迁移，则可能导致两个 RegionServer 都会为 Peer 开辟一个复制线程，这样会带来几个问题：
- 源集群中写数据顺序和 Peer 集群中的执行顺序不一致
- 在极端情况下由于数据写入执行顺序乱序导致数据不一致(Delete 数据先于 Put 数据到达，导致 Put 数据不会被清除掉)

非串形复制的根本原因在于 Region 从一个 RegionServer 移动到另外一个 RegionServer 的过程中，Region的数据会分散在两个 RegionServer 的 HLog 上，而两个 RegionServer 完全独立地推送各自的 HLog，从而导致同一个 Region 的数据并行写入 Peer 集群。一个简单的解决思路是将 Region 的数据按照 Region 移动发生的时间切分为 N 段，在每段复制之前需要保证前面所有段的数据已经复制完成即可解决这个问题。

HBase 社区版根据这种思想提供了实现，包含三个概念：
- Barrier：每一次 Region 重新 Assign 到新的 RegionServer 时，新 RegionServer 打开 Region 前能读到的最大 SequenceId，因此每打开一次 Region 就会产生一个新的 Barrier，Region 在打开 N 次之后就会有 N 个 Barrier 把该 Region 的 SequenceId 数轴划分为 N+1 个区间
- LastPushedSequenceId：表示该 Region 最近一次成功推送到 Peer 集群的 HLog 的 SequenceId，事实上每次成功推送一个 Entry 到 Peer 集群后都需要将 LastPushedSequenceId 更新到最新的值
- PendingSequenceId：表示该 Region 当前读到的 HLog 的 SequenceId

HBase 集群值需要对每个 Region 都维护一个 Barrier 列表和 LastPushedSequenceId，就能按照规则确保在上一个区间的数据完全推送之后再推送下一个区间的数据，如果上一个区间的 HLog 还没有完全推送结束，就会休眠一段时间之后再检查一次上一个区间是否推送结束，若推送结束则开始推送本区间的数据

### 同步复制
HBase 的复制一般是异步的，即 HBase 客户端写入数据到主集群之后就返回，然后主集群再异步的把数据依次推送到备份集群。若主集群因意外或者 Bug 无法提供服务时，备份集群的数据是比主集群少的，通过同步复制的方式可以保证备份集群的数据和主集群的数据保持一致。

同步复制的核心思想是 RegionServer 在收到写入请求之后不仅会在主集群上写一份 HLog 日志，还会同时在备份集群上写一份 RemoteWAL 日志，在数据写入时只有主集群上的 HLog 日志和备集群上的 RemoteWAL 都写入成功之后才会返回给客户端。除此之外，主机群到备集群之间还会开启异步复制链路，若主集群的某个 HLog 通过异步复制完全推送到备份集群，那么这个 HLog 在备份集群上对应的 RemoteWAL 则被清理，因此可以认为 RemoteWAL 是指那些已经成功写入主集群但尚未被异步复制成功推送到备份集群的数据，对主集群的每次写入备份集群都不会丢失，当主集群发生故障，只需要回放 RemoteWAL 日志到备份集群，备份集群就可以马上恢复数据为线上提供服务。

为了方便同步复制，主集群和备集群的同步复制状态分为 4 种：
- Active：这种状态的集群将在远程集群上写 RemoteWAL 日志，同时拒绝接收来自其他集群的复制数据。一般情况下同步复制的主集群会处于 Active 状态
- Downgrade Active：这种状态的集群将跳过写 RemoteWAL 流程，同时拒绝接收来自其他集群的复制数据，一般情况下同步复制中的主集群因备份集群不可用卡住后，会被降级为 DA 状态用来满足业务的实时读写
- Standby：这种状态的集群不允许 Peer 内的表被客户端读写，它只接收来自其他集群的复制数据，同时确保不会将本集群中 Peer 内的表数据复制到其他集群上，一般情况下同步复制中的备份集群会处于 Standby 状态
- Node：表示没哟开启同步复制

#### 建立同步复制
建立同步复制分为三步：
- 在主集群和备份集群分别建立一个指向对方集群的同步复制 peer，这是主集群和备份集群的状态默认为 DA
- 通过 transit_peer_sync_replication_state 命令将备份集群的状态从 DA 切换成 Standby
- 将主集群状态从 DA 切换成 Active

#### 备份集群故障处理
当备份集群发生故障时，处理流程如下：
- 将主机群状态从 Active 切换为 DA，此时备份集群已经不可用，所有写到主集群的请求可能会因为写 RemoteWAL 失败而失败，因此需要将主集群从 Active 切换为 DA，这样就不需要写 RemoteWAL 了，保证了业务能正常读写 HBase 集群
- 在确保备份集群恢复后，直接把备份集群状态切换为 S，在备份集群恢复之前的数据通过异步复制同步到了备份集群，恢复之后的数据通过同步复制的方式同步到备份集群
- 将主机群状态从 DA 切换成 Active

#### 主集群故障处理
当主集群发生故障时，处理流程如下：
- 将备份集群的状态从 Standby 切换成 DA，之后备份集群不再接收来自主集群复制过来的数据，在备份集群状态从 Standby 切换成 DA 的过程中会先回放 RemoteWAL 日志，保证主备集群数据一致性后再让业务方把读写流量都切换到备份集群
- 主集群恢复后，虽然业务已经切换到原来的备份集群上，但是原来的主集群还认为自己是 Active 状态
- 由于主集群认为自己是 Active 状态，所有备份集群上的数据不能同步到主集群，这时可以直接将主集群状态切换成 Standby，等原来的备份集群上的数据都同步到主集群之后，两个集群的数据将最终保持一致
- 把备份集群从 DA 切换成 A，继续开启同步复制保持数据一致性


同步复制在数据最终一致性和集群可用性方面比异步复制更加优秀，但是由于需要写一份 RemoteWAL 导致写性能有所下降(13%)、网络带宽以及存储空间更大，且整个过程也更加复杂。