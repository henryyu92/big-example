## 调优



### 日志

- `log.cleanup.policy`：设置日志清理策略，默认是 `delete` 可以设置成 `delete,compact` 同时开启日志删除和日志压缩
- `log.cleaner.enable`：开启日志压缩策略时需要设置为 `true`，默认开启
- `log.retention.check.interval.ms`：后台日志检查线程的检测周期，默认是 300000，也就是 5 分钟
- `log.retention.ms`：表示分段中 `timestamp` 最大的消息的保留时间，超过则需要删除分段，默认是 7 天
- `log.retention.bytes`：分区能够保留的最大消息字节数，日志超过阈值则需要删除，默认是 -1 表示保留所有数据
- `log.cleaner.threads`：执行日志压缩的线程数，默认是 1

### 索引





- `log.cleaner.enable`|true|是否开启日志清理功能|
- `log.cleaner.min.cleanable.ratio`|0.5|限定可执行清理操作的最小污浊率|
- `log.cleaner.threads`|1|用于日志清理的后台线程数|
- `log.cleanup.policy`|delete|日志清理策略|
- `log.index.interval.bytes`|4096|每隔多少个字节的消息量写入就添加一条索引|
- `log.index.size.max.bytes`|10485760(10M)|索引文件的最大值|
- `log.message.format.version`|2.0-IVI|消息格式中的版本|
- `log.message.timestamp.type`|CreateTime|消息中的时间戳类型|
- `log.retention.check.interval.ms`|300000|日志清理的检查周期|





- `log.flush.interval.message`|Long.MAX_VALUE|日志文件中消息存入磁盘的阈值|
- `log.flush.interval.ms`||刷新日志文件的时间间隔，如果不配置则依据 log.flush.scheduler.interval.ms 值|
- `log.flush.scheduler.interval.ms`|Long.MAX_VALUE|检查日志文件是否需要刷新的时间间隔|
- `log.retention.bytes`|-1|日志文件的最大保留大小|
- `log.retention.hours`|168(7天)|日志存留时间，优先级最低|
- `log.retention.minutes`||和 log.retention.hours 一样，优先级居中|
- `log.retention.ms`||和 log.retention.hours 一样，优先级最高|
- `log.roll.hours`|168(7天)|经过多长时间后强制创建日志分段|
- `log.roll.ms`||和 log.roll.hours 一样，优先级较高|
- `log.segment.bytes`|1073741824(1G)|日志分段文件的最大值，超过这个值会强制创建一个新的日志分段|
- `log.segment.delete.delay.ms`|60000|从操作系统删除文件前的等待时间|