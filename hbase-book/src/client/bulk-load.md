## BulkLoad

HBase 提供了将数据生成 HFile 然后直接加载到对应的 Region 下的 Column Family 内，在生成 HFile 时服务端不会有任何 RPC 调用，只有在 load HFile 时会调用 RPC。bulkLoad 是一种完全离线的快速批量写入方案，不会对集群产生巨大压力