## 调优



### 日志

- `log.cleanup.policy`：设置日志清理策略，默认是 `delete` 可以设置成 `delete,compact` 同时开启日志删除和日志压缩
- `log.cleaner.enable`：开启日志压缩策略时需要设置为 `true`，默认开启
- `log.retention.check.interval.ms`：后台日志检查线程的检测周期，默认是 300000，也就是 5 分钟
- `log.retention.ms`：表示分段中 `timestamp` 最大的消息的保留时间，超过则需要删除分段，默认是 7 天
- `log.retention.bytes`：分区能够保留的最大消息字节数，日志超过阈值则需要删除，默认是 -1 表示保留所有数据
- `log.cleaner.threads`：执行日志压缩的线程数，默认是 1

### 索引