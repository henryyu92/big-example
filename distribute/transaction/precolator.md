## Precolator

precolator 算法是对二阶段提交的优化实现，其提交也分为两个阶段：prewrite 阶段和 commit 阶段。

### Prewrite

- 事务开始时从全局授时服务 (TSO) 获取一个 timestamp 作为事务的 tart_ts

