## Precolator

Prelocator 是在 BigTable 之上实现的，利用了 BigTable 的单行事务能力，仅仅依靠客户端侧的协议和一个全局授时服务器实现了跨机器的多行事务。

Precolator 为每个列族增加了两列：

- lock：存储事务过程中的锁信息
- write：存储当前行可见的版本号

precolator 算法是对二阶段提交的优化实现，其事务提交流程也分为两个阶段：prewrite 阶段和 commit 阶段。

### Prewrite

- 事务开始时从全局授时服务 (TSO) 获取一个 timestamp 作为事务的 tart_ts
- 选择事务中的某个写操作作为 primary，其他写操作则为 secondary。primary 作为事务提交的互斥点，标记事务的状态
- 先对 Primary 进行预写操作，成功后在对 secondary 进行预写操作，每个预写操作都需要执行检查：
  - 写入行的 write 列是否存在 [start_ts, max) 的数据存在，如果存在则说明有新的事务已经提交，此时需要回滚事务
  - 写入行的 lock 列是否存在锁，如果存在则回滚事务
- 检查完成后将数据以 start_ts 版本写入 data 列，但是不更新 write 列，此时数据对其他事务不可见
- 对写操作加锁，也就是以更新 lock 列，primary 操作直接更新为 primary，secondary 操作更新为行健和列名

### Commit

- 客户端从全局授时服务(TSO)获取时间戳作为事务提交的时间戳 commit_ts
- 