# 调优



- `log.cleanup.policy`：设置日志清理策略，默认是 `delete` 可以设置成 `delete,compact` 同时开启日志删除和日志压缩
- `log.cleaner.enable`：开启日志压缩策略时需要设置为 `true`
- `log.retention.check.interval.ms`：后台日志检查线程的检测周期，默认是 300000，也就是 5 分钟
- `retention.ms`：表示分段中 `timestamp` 最大的消息的保留时间，超过则需要删除分段，默认是 

