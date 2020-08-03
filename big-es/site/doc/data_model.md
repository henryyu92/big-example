## 数据模型

ES 的数据模型基于主从模式，多个副本中存在一个主副本和多个从副本，所有的数据写入操作都进入主副本，当主副本出现故障无法提供服务时，会从其他副本中选择合适的副本作为新的主副本。

数据写入流程：
- 写请求进入主副本节点，节点为该操作分配 SN(Serial Number)，使用该 SN 创建 UpdateRequest 结构，然后将该 UpdateRequest 插入自己的 prepare list
- 主副本节点将携带 SN 的 UpdateRequest 发往从副本节点，从节点收到后同样插入 prepare list，完成后给主副本节点回复一个 ACK
- 一旦主副本节点接收到所有从副本节点的响应，确定该数据已经被正确写入所有的从副本节点，此时认为可以提交了，将 UpdateRequest 放入 committed list
- 主副本节点向从副本节点发送一个 commit 通知，告诉自己的 commit point 位置，从副本节点收到通知后根据指示移动 committed point 到相同的位置


### PacificA 算法
### 配置管理

全局的配置管理器负责管理所有副本组的配置，节点可以向管理器提出添加/移除副本的请求，每次请求都需要附带当前配置版本号，只有这个版本号和管理器记录的版本号一致才会被执行，如果执行成功则这个新的配置会被赋予新的版本号。

全局的配置管理器维护着权威的配置信息，但是其他各节点上的配置信息不一定同步，必须处理旧的主副本和新的主副本同时存在的情况。使用租约(lease)机制可以解决这个问题，主副本定期向其他副本获取租约，这个过程会产生两种情况：
- 如果主副本节点在一定时间内(lease period) 未收到从副本节点的租约回复，则主副本节点认为从副本节点异常，向配置管理器汇报，将该异常从副本节点从副本组中移除，同时将自己降级，不再作为主副本节点
- 如果从副本节点在一定时间内(grace period) 未收到主副本节点的租约请求，则认为主副本节点异常，向配置管理器汇报，将主副本从副本组中移除，并将自己提升为新的主副本，如果存在多个主副本，则哪个从副本先执行成功哪个从副本就被提升为新的主副本

只要 grace period >= lease period 则租约机制就可以保证主副本会比任意从副本先感知到租约失效，同事任何一个从副本只有在它租约失效时才会争取当新的主副本，因此保证了新主副本产生之前，旧的主副本已经降级，不会产生两个主副本

### ES 数据模型

ES 中每个 索引都会被拆分成多个分片，并且每个分片都有多个副本，这个副本称为 replication group。在删除或者添加文档的时候，各个副本必须同步，否则从不同副本读取的数据会不一致。保持分片副本之间的同步，以及从中读取的过程称为数据副本模型。



ES 的数据副本模型基于主备模式，主分片是所有索引操作的入口，它负责验证索引操作是否有效，一旦主分片接受一个索引操作，主分片的副分片也会接受该操作。



每个索引操作首先会使用 routing 参数解析到副本组，通常基于文档 ID，一旦确定副本组就会内部转发该操作到分片组的主分片中。主分片负责验证操作和转发到其他副分片，ES 维护一个可以接收该操作的分片的副本列表，称为同步副本列表(in-sync replicas)，并由 Master 节点维护，同步副本列表中的分片会保证已经成功处理所有索引和删除操作，并返回 ACK。主副本分片负责维护不同副本的数据一致性，需要将索引操作转发到这个列表中的每个副本。



ES 数据写入基本流程：

1. 请求到达协调节点，协调节点先验证操作，如果有错就拒绝该操作，然后根据当前集群状态，请求被路由到主分片所在的节点
2. 主分片节点执行请求，如果验证请求操作失败则拒绝操作
3. 主分片操作成功后，转发该操作到 in-sync 副本组的所有副分片
4. 一旦所有的副分片成功执行操作并回复主分片，主分片会把请求执行成功的信息返回给协调节点，协调节点返回给客户端



通过 ID 读取是非常轻量级的操作，而一个巨大的复杂的聚合查询请求需要消耗大量 CPU 和内存资源。主从模式的一个好处是保证所有的分片副本都是一致的。当一个读请求被协调节点接收，这个节点负责转发它到其他涉及相关分片的节点，并整理响应结果发送给客户端，接收用户请求的这个节点称为协调节点。



ES 数据读取基本流程：

1. 协调节点把读请求转发到相关分片，大多数搜索都会需要从多个分片中读取，然后将这些分片的数据进行聚合
2. 从副本组中选择一个相关分片的活跃副本，它可以是主分片或副分片
3. 发送分片请求到被选中的副本
4. 合并结果并给客户端返回响应



### Allocation ID

Allocation ID 用于主分片选举策略，每个分片有自己唯一的 Allocation ID，同时集群元信息中有一个列表，记录了哪些分片拥有最新的数据。



ES 的数据模型会假定其中一个数据副本为权威副本，称为主分片，所有的索引操作写主分片，完成后主分片所在节点会负责把更改转发到活跃的备份副本，称为副分片。如果当前主分片临时或永久地变为不可用状态，则另一个分片副本被提升为主分片。



分片分配就是决定哪个节点应该存储在一个活跃分片的过程。分片决策的过程在主节点完成，并记录在集群状态中，该数据结构还包含了其他元数据，如索引设置及映射。分配决策分为两部分：哪个分片应该分配到哪个节点，以及哪个分片作为主分片，哪些作为副分片。主分片广播集群状态到集群的所有节点，这样每个节点就有了集群的状态，就可以实现对请求的智能路由。



每个节点都会通过检查集群状态来判断某个分片是否可用，如果一个分片被指定为主分片，则这个节点只需要加载本地分片副本用于搜索；如果一个分片被指定为副分片，则节点首先需要从主分片所在节点复制差异数据。



在创建新索引时，主节点在选择哪个节点作为主分片方面有很大的灵活性，会将集群均衡和其他约束考虑在内，为了确保安全，主节点必须确保被选为主分片的副本含有最新数据，为此 ES 使用 Allocation ID 的概念用于区分不同分片。



Allocation ID 由主节点在分片分配时指定，并由数据节点存储在磁盘中，紧邻实际的数据分片。主节点负责追踪包含最新数据副本的子集。集群状态存储于集群的主节点和所有数据节点，对集群状态的更改由 Discovery 模块实现一致性支持。



当分配主分片时，主节点检查磁盘中的 Allocation ID 是否会在集群状态的 in-sync 集合中出现


