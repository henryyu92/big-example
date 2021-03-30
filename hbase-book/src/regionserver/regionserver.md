## RegionServer

RegionServer 是 HBase 系统中最核心的组件，由 HLog，BlockCache 和多个 Region 组成。`HRegionServer` 是 RegionServer 的实现，负责对外提供服务并且管理分布在上面的 Region。

`HRegionServer` 在启动时会在后台运行多个线程用于检测当前 RegionServer 上的各个组件：

- `compactSplitThread`：负责处理 Minor 合并，并且在合适时执行 Region 分裂
- `compactionChecker`：负责检查 Region 是否需要进行合并
- `periodicFlusher`：负责定期将 MemoStore 刷盘
- `movedRegionsCleaner`：负责清理已经移除的 Region
