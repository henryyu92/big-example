## Region

Region 是 HBase 的数据分片，Region 由 Store 组成，每个 Region 包含的 Store 数和表的列簇相等。写入 Region 的数据最终会形成多个 StoreFile 文件以 HFile 格式存储在 HDFS。

HBase 为每个 Region 维护了一个状态，并将 Region 的状态持久化到 `hbase:meta` 表，而 `hbase:meta` 表的 Region 的状态持久化在 ZooKeeper 中。

- `OFFLINE`：Region 处于下线状态
- `OPENING`：Region 在被打开的过程中
- `OPEN`：Region 处于打开状态，并且 RegionServer 已经通知了 Master
- `FAILED_OPEN`： Region 打开过程中失败
- `CLOSING`：Region 处于被关闭的过程中
- `CLOSED`：RegionServer 关闭了 Region，并且通知了 Master
- `FAILED_CLOSE`：RegsionServer 关闭 Region 的过程中失败
- `SPLITTING`：Resion 正在进行分裂的过程中
- `SPLIT`：Region 已经完成分裂，并且已经通知 Master
- `SPLITTING_NEW`：当前 Region 是在正在分裂的过程中创建
- `MERGING`：Region 正在进行合并过程
- `MERGED`：Region 已经合并完成
- `MERGING_NEW`：Region 是正在进行合并过程中创建

### Region 分裂

`RegionServer` 接收到的数据存满 `MemStore` 后会触发刷盘生成文件，`RegionServer` 会将多个刷盘形成的文件压缩成大文件，每次刷盘或者 compaction 操作都会时 Region 中的数据发生变化，`RegionServer` 根据指定的分裂策略决定是否需要分裂 `Region`。

Region 分裂是实现分布式可扩展的基础，HBase 定义多种分裂策略，当 Region 满足配置的分裂策略的条件时就会触发分裂操作。

#### 分裂策略

Region 分裂策略决定 Region 是否需要分裂，HBase 定义了多种分裂策略，不同的分裂策略适用于不同的应用场景：

- `ConstantSizeRegionSplitPolicy`：Region 中某个 Store 超过设置阈值`hbase.hregion.max.filesize` 就会触发分裂。阈值设置过大会导致小表很难分裂，设置过小会导致大表分裂过多 Region
- `IncreasingToUpperBoundRegionSplitPolicy`：Region 中某个 Store 超过阈值就会触发分裂，阈值为 `hbase.hregion.max.filesize` 和 `2 * hbase.hregion.memstore.flush.size * tableRegionsCount * tableRegionsCount * tableRegionsCount` 的最小值，其中 `tableRegoinCount` 为当前表在当前 RegionServer 上的 Region 数量
- `SteppingSplitPolicy`：继承自 `IncreasingToUpperBoundRegionSplitPolicy`，阈值策略相同，不同的是会判断当前表在当前 RegionServer 上的 Region 数量，如果为 1 的话则阈值为 `2 * hbase.hregion.memstore.flush.size`，否则就是 `hbase.hregion.max.filesize`

HBase 默认使用 `SteppingSplitPolicy` 分裂策略，通过继承 `IncreasingToUpperBoundRegionSplitPolicy`  可以自定义分裂策略。分裂策略可以通过配置文件全局设置，也可以在创建表的时候设置：

```xml
<property>
    <name>hbase.regionserver.region.split.policy</name>
    <value>org.apache.hadoop.hbase.regionserver.IncreasingToUpperBoundRegionSplitPolicy</value>
</property>
```

```java
TableDescriotorBuilder tableDesc = TableDescriptorBuilder.newBuilder(TableName.of("tableName"));
tableDesc.setRegionSplitPolicyClassName(CustomSplitPolicy.class.getName());
```

#### 分裂点

Region 触发分裂之后首先需要找到分裂点，分裂策略定义了获取分裂点的方法，默认的分裂点从 Region 中最大的 Store 中获取。

```java
protected byte[] getSplitPoint() {
    // 外部指定分裂点，通常是手动触发分裂
    byte[] explicitSplitPoint = this.region.getExplicitSplitPoint();
    if (explicitSplitPoint != null) {
        return explicitSplitPoint;
    }
    List<HStore> stores = region.getStores();

    byte[] splitPointFromLargestStore = null;
    long largestStoreSize = 0;
    // 从最大的 Store 中获得分裂点
    for (HStore s : stores) {
        Optional<byte[]> splitPoint = s.getSplitPoint();
        // Store also returns null if it has references as way of indicating it is not splittable
        long storeSize = s.getSize();
        if (splitPoint.isPresent() && largestStoreSize < storeSize) {
            splitPointFromLargestStore = splitPoint.get();
            largestStoreSize = storeSize;
        }
    }

    return splitPointFromLargestStore;
}
```

#### 分裂流程

Region 分裂过程比较复杂，涉及父 Region 中 HFile 文件分裂、子 Region 生成、meta 数据更改等很多子步骤，HBase 使用状态机的方式保存分裂过程中的每个子步骤状态，系统根据当前所属的状态决定是否回滚。

- 检查 Region 是否可以进行分裂，然后将 Region 的状态设置为 `SPLITTING`
- 关闭父 Region 并触发父 Region 的的 flush 操作将数据全部持久化到磁盘，此后客户端到该 Region 的请求都会抛出 `NotServingRegionException`
- 在父 Region 的文件存储目录下创建临时文件夹 `.split`，并在临时文件夹下创建两个子 Region 的存储目录，然后在子 Region 的列簇对应的存储目录中生成 reference 文件，文件名为 `hfile_name.region_name`，因此根据 reference 文件名即可定位该文件指向父 Region 的 HFile 文件。reference 文件是一个引用文件，文件内容并不是数据，而是由分裂点 splitKey 和 boolean 类型的变量表示该文件引用的是父文件的上部分 (true) 还是下部分 (false)，使用 Hadoop 命令可以查看  reference 文件具体内容 `hadoop dfs -cat /<namespace>/<table>/<region>/.split/<colum_family>/<reference>`
- 将子 Region 对应的存储文件拷贝到数据目录下，形成两个新的 Region
- 修改 `meta` 表，将父 Region 的 `split` 和 `offline` 设置为 true，并记录两个子 Region，然后将父 Region 下线。父 Region 下线后并不会立马删除，而是在执行 Major Compaction 的时候删除
- 开启生成的两个子 Region，并对外提供服务

![Region 分裂](../img/region-split.png)

分裂生成的子 Region 对应的文件为 reference 文件，其中没有存储真实的数据，数据查询需要通过 reference 文件。通过 reference 文件查找数据分为两步：

- 根据 reference 文件名(父 Region 名 + HFile 文件名)定位到真实数据所在文件路径
- 根据 reference 文件内容记录的两个重要字段确定实际扫描范围，top 字段表示扫描范围是 HFile 上半部分还是下半部分，如果 top 为 true 则表示扫描的范围为 [firstkey, splitkey)，如果 top 为 false 则表示扫描的范围为 [splitkey, endkey)

父 Region 的数据迁移到子 Region 目录的时间发生在子 Region 执行 Major Compaction 时，在子 Region 执行 Major Compaction 时会将父 Region 目录中属于该子 Region 中所有的数据读取出来，并写入子 Region 目录数据文件中。

Master 会启动一个线程定期遍历检查所处于 splitting 状态的 Region，确定父 Region 是否可以被清理，检查过程分为两步：

- 检测线程首先会在 meta 表中读出所有 spit 列为 true 的 Region，并加载出其分裂后生成的两个子 Region(meta 表中 splitA 和 splitB 两列)
- 检查两个子 Region 是否还存在引用文件，如果都不存在引用文件就可以认为该父 Region 对应的文件可以被删除

HBCK 可以查看并修复在 split 过程中发生异常导致 region-in-transaction 问题，主要命令包括：

```shell
# 将下线的父 Region 强制上线
-fixSplitParents
# 将父 Region 下线并保留子 Region
-removeParents
-fixReferenceFiles
```

#### 手动分裂

### Region 迁移

HBase 的集群负载均衡、故障恢复功能都是建立在 Region 迁移的基础之上，HBase 由于数据实际存储在 HDFS 上，在迁移过程中不需要迁移实际数据而只需要迁移读写服务即可，因此 HBase 的 Region 迁移是非常轻量级的。

Region 迁移的过程分为 unassign 和 assign 两个阶段，其中 unassign 表示 Region 从 RegionServer 上下线，assign 表示 Region 在 RegionServer 上上线。

#### unassign

- Master 生成事件 M_ZK_REGION_CLOSING 并更新到 ZK 组件，同时将本地内存中该 Region 的状态修改为 PENDING_CLOSE
- Master 通过 RPC 发送 close 命令给拥有该 Region 的 RegionServer，令其关闭该 Region
- RegionServer 接收到 Master 发送过来的命令后，生成一个 RS_ZK_REGION_CLOSING 事件，更新到 ZK
- Master 监听到 ZK 节点变动后，更新内存中 Region 的状态为 CLOSING
- RegionServer 执行 Region 关闭操作。如果该 Region 正在执行 flush 或者 Compaction，则等待其完成；否则将该 Region 下的所有 MemStore 强制 flush，然后关闭 Region 相关服务
- RegionServer 执行完 Region 关闭操作后生成事件 RS_ZK_REGION_CLOSED 更新到 ZK，Master 监听到 ZK 节点变化后，更新该 Region 的状态为 CLOSED

#### assign

- Master 生成事件 M_ZK_REGION_OFFLINE 并更新到 ZK 组件，同时将本地内存中该 Region 的状态修改为 PENDING_OPEN
- Master 通过 RPC 发送 open 命令给拥有该 Region 的 RegionServer，令其打开 Region
- RegionServer 接收到命令之后，生成一个 RS_ZK_REGION_OPENING 事件，并更新到 ZK
- Master 监听到 ZK 节点变化后，更新内存中 Region 的状态为 OPENING
- RegionServer 执行 Region 打开操作，初始化相应的服务
- 打开完成之后生成事件 RS_ZK_REGION_OPENED 并更新到 ZK，Master 监听到 ZK 节点变化后，更新该 Region 状态为 OPEN

整个 unassign 和 assign 过程涉及 Master、RegionServer 和 ZK 三个组件，三个组件的职责如下：

- Master 负责维护 Region 在整个过程中的状态变化
- RegionServer 负责接收 Master 的指令执行具体 unassign 和 assign 操作，即关闭 Region 和打开 Region 的操作
- ZK 负责存储操作过程中的事件，ZK 有一个路径为 /hbase/region-in-transaction 节点，一旦 Region 发生 unassign 操作，就会在这个节点下生成一个子节点，Master 会监听此节点，一旦发生任何事件，Master 会监听到并更新 Region 的状态

Region 在迁移的过程中涉及到多个状态的变化，这些状态可以记录 unassign 和 assign 的进度，在发生异常时可以根据具体进度继续执行。Region 的状态会存储在三个区域：

- meta 表，只存储 Region 所在的 RegionServer，并不存储迁移过程中的中间状态，如果 Region 迁移完成则会更新为新的对应关系，如果迁移过程失败则保存的是之前的映射关系
- master 内存，存储整个集群所有的 Region 信息，根据这个信息可以得出此 Region 当前以什么状态在哪个 RegionServer 上。Master 存储的 Region 状态变更都是由 RegionServer 通过 ZK 通知给 Master 的，所以 Master 上的 Region 状态变更总是滞后于真正的 Region 状态变更，而 WebUI 上看到的 Region 状态都是来自于 Master 的内存信息
- ZK，存储的是临时性的状态转移信息，作为 Master 和 RegionServer 之间反馈 Region 状态的通信，Master 可以根据 ZK 上存储的状态作为依据据需进行迁移操作

### Region 合并

Regin 合并用于空闲 Region 很多从而导致集群管理运维成本增加的场景，通过使用在线合并功能将这些 Region 与相邻的 Region 合并，减少集群中空闲的 Region 个数。

Region 合并的主要流程如下：

- 客户端发送 merge 请求给 Master
- Master 将待合并的所有 Region 都 move 到同一个 RegionServer 上
- Master 发送 merge 请求给该 RegionServer
- RegionServer 启动一个本地事务执行 merge 操作
- merge 操作将待合并的两个 Region 下线，并将两个 Region 的文件进行合并
- 将这两个 Region 从 hbase:meta 中删除，并将新生成的 Region 添加到 hbase:meta 中
- 将新生成的 Region 上线

HBase 使用 merge_region 命令执行 Region 合并，merge_region 操作是异步的，需要在执行一段时间之后手动检测合并是否成功，默认情况下 merge_region 只能合并相邻的两个 Region，如果可选参数设置为 true 则可以强制合并非相邻的 Region，风险较大不建议生产使用：

```shell
merge_region 'regionA', 'regionB', true
```
