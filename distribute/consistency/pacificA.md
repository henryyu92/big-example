## PacificA

PacificA 算法是一种用于日志复制系统的分布式一致性算法。该算法有几个特点：

- 配置管理和数据管理分离，配置管理需要引入其他算法来保证数据一致性
- 任意时刻读取到的数据都是最新的数据
- 每个副本都有全量的数据，只要有一个副本可用就可以保证读写

ParcificA 由两部分组成：存储集群和配置管理集群。

- 存储集群：负责系统数据的读取和更新，通过多副本方式保证数据的可靠性和可用性
- 配置管理集群：维护副本信息，包括副本的参与结点、主副本结点、当前副本的版本等，需要通过 Paxos 协议保证数据的一致性

### 名词

- `Replica Group`：数据分片的副本集合，包含一个 primary 副本和多个 secondary 副本
- `Configuration`：副本集合的元数据，描述了当前副本组的信息，如副本集合、primary 副本等
- `Configuration Version`：配置的版本号，每次更新时加 1
- `Configuration Manager`：管理数据存储集群配置信息的全局组件，需要采用 Paxos 协议保证配置在全局的一致性。副本会向配置管理器发送配置变更请求，如果请求中携带的版本号不正确则拒绝修改

### 不变式

PacificA是一个读写都满足强一致的算法，它通过三个不变式保证了读写的primary的唯一性，读写的强一致性，故障恢复的可靠性。

- `Primary Invariant`：

 update操作时，要求所有的secondary均prepare当前update，primary commit当前update，保证了Committed Invariant, 使得读操作可以获取到最新数据，primary故障时，secondary也有全量的数据。

故障恢复机制保证了当secondary被选为primary时，其commit包含之前primary或secondary的commit，保证了Reconfiguration Invariant，使得在故障恢复后数据不会有丢失。

#### Primary Invariant

它把数据的一致性和配置的一致性分开，使用额外的一致性组件（Configuration Manager）维护配置的一致性，结合lease机制保证了Primary Invariant，使得在同一时刻有且仅有一个primary。

在pacificA算法中，要保证primary不变式Primary Invariant，即

- 同一个时刻只有一个副本认为自己是primary
- configuration Manager也认为其是primary

#### Reconfiguration Invariant

重新配置不变式：当一个新的primary在T时刻完成reconfiguration,那么T时刻之前任意节点（包括原primary）的committedList都是新的primary当前committedList的前缀。

该不变式保证了reconfiguration过程中没有数据丢失，由于update机制保证了任意的sencondary都有所有的数据，而reconfiguration重新选primary要求新的primary commit其所有的prepareList,因此这个不变式可以得到保证。

**Committed Invariant**：

- Secondary Committed List 为 Primary Committed List 的前缀，即 primary committed 领先于 secondary committed
- Primary Committed List 为 Secondary PreparedList 的前缀，即 Secondary PreparedList 拥有 primary committed 的所有数据

### 读写流程

pacificA副本数据使用主从式框架来保证数据一致性。分片的多个副本中存在一个主副本Primary和多个从副本Secondary。所有的数据更新和读取都进入主副本，当主副本出现故障无法访问时系统会从其他从副本中选择合适的节点作为新的主。

#### 查询

query 流程中查询只能在 primary 上进行，primary 根据最新 commit 的数据返对应的值。

#### 更新

更新也是在 primary 上进行的，更新流程：

- primary 为 updateRequest 分配一个 SN(Serial Number)
- primary 将这个 updateRequest 加入自己的 Prepared List，同时向所有的 Secondary 发送 Prepare 请求，要求将这个 updateRequest 加入到 prepared List
- 当所有的 secondary 都将 updateRequest 加入到 prepared List 后，primary 执行 update，也就是将 updateRequest 从 prepared List 移动到 committed List 中，同时将 commit point 移动到该 commit
- primary 返回客户端 update 操作成功
- 当下一次 primary 向 secondary 发送请求时，会带上 primary 当前的 commit point，此时 secondary 才会提升自己的 commit point

### 故障检测

PacificA 通过契约 (lease) 的方式进行 primary 和 secondary 间的互相检测。primary 会定期(lease period) 向所有 Secondary 发送心跳来获取 lease，如果某个节点没有回复则说明该节点故障，primary 会向 Configuration Manager 请求移除该 secondary。如果超过 grace period 时间 secondary 没有收到 primary 的请求，则认为 primary 鼓掌，然后向 Configuration Manager 请求移除 primary 并将自己设置为 primary，如果成功则实现了故障转移。

- 当多个 secondary 发现 primary 故障时，先请求的成为 primary
- 当出现网络分区时，primary 会请求移除 secondary，而 secondary 会请求移除 primary，但是由于 lease period < grace period，因此 primary 先于 secondary 发现故障并将 secondary 移除

```
PacificA 算法需要时间一致的保证，因此机器之间的时钟漂移对其有影响
```

#### secondary 故障

当一个scondary故障时，primary在向该secondary发送lease请求时会超时，primary向Configuration Manage发送Reconfiguration请求将该secondary从Configuration中移除

假设某个Primary和Secondary发生了网络分区，但是都可以连接Configuration Manager。这时候Primary会检测到Secondary没有响应了，Secondary也会检测到Primary没有响应。此时两者都会试图发起Reconfiguration，将对方从Replica Group中移除，这里的策略是First Win的原则，谁先到Configuration Manager中更改成功，谁就留在Replica Group里，而另外一个已经不属于Replica Group了，也就无法再更新Configuration了。由于Primary会向Secondary请求一个Lease，在Lease有效期内Secondary不会执行Reconfiguration，而Primary的探测间隔必然是小于Lease时间的，所以我认为这种情况下总是倾向于Primary先进行Reconfiguration，将Secondary剔除。

#### primary 故障

当primary故障时， secondary在超过grace period没有收到primary的请求，就会向Configuration Manager发送Reconfiguraiont请求

要求将primary从configuration中移除并将自己选为primary。多个secondary竞争成为primary时，遵循first win原则。

当一个secondary被选为primary后 ,它会向所有的secondary发送prepare请求，要求所有的sencodary均以其pareparedList为准进行对齐，当收到所有secondary的ack后，primary将自己的commit point移动到最后，这个时候primary才能对外提供服务。

当一个Secondary变成Primary后，需要先经过一个叫做Reconciliation的阶段才能提供服务。由于上述的Commited Invariant，所以原先的Primary的Committed List一定是新的Primary的Prepared List的前缀，那么我们将新的Primary的Prepared List中的内容与当前Replica Group中的其他节点对齐，相当于把该节点上未Commit的记录在所有节点上再Commit一次，那么就一定包含之前所有的Commit记录。

#### 网络分区

网络分区场景下，primary认为secondary故障，secondary认为primary故障，但由于lease period小于grace period，所以primary会先与secondary发现故障，并向Congfiguration Manager发送请求移除secondary

#### 新节点加入

新节点加入时，首先会先成为secondary candidate, 然后追平primary的preparedList,然后申请成为secondary。还有一种情况是之前故障的节点恢复加入，这个时候会复用之前的preparedlist并追平secondary的preparedlist, 然后申请成为secondary。

还有一种情况时，如果一个节点曾经在Replica Group中，由于临时发生故障被移除，现在需要重新加回来。此时这个节点上的Commited List中的数据肯定是已经被Commit的了，但是Prepared List中的数据未必被Commit，所以应该将未Commit的数据移除，从Committed Point开始向Primary请求数据。







https://www.zhihu.com/question/53738589