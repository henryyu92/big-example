# 参数调优
生产者客户端在初始化时可以配置多个参数，这些参数影响着消息发送的整个流程，通过合理的配置这些参数的值可以提升 Kafka 生产者客户端的性能。


|参数名|说明|默认值|
|:-----:|:-----|:-----:|
|`acks`|指定消息写入成功需要确认的副本数，有三个可选值：<br/> <li>`acks=0` 表示消息从生产者发送之后即认为写入成功，网络异常时消息会丢失</li><li>`acks=1` 表示 leader 副本确认后才返回写入成功，消息同步之前 leader 异常会丢失消息</li><li>`acks=-1`表示 ISR 集合中的所有副本确认后才返回成功，能够保证消息不丢失</li>|1|
|`retries`|可重试异常时消息发送重试的次数，如果 `max.in.flight.request.per.connection` 值大于 1 则会出现消息乱序|0|
|`retry.backoff.ms`|消息发送重试之间的时间间隔|100|
|`buffer.memory`|消息缓冲区的大小，超过之后追加消息会阻塞直至超时|33554432(32M)|
|`batch.size`|缓冲区中可复用的 ProducerBatch 大小|16384(16K)|
|`max.block.ms`|获取 Metadata 并且追加消息到缓冲区的超时时间|60000(60s)|
|`metadata.max.age.ms`|Metadata 更新间隔，超过此间隔则强制更新|30000(5m)|
|`linger.ms`|没有达到 `batch.size` 的 ProducerBatch 等待时长，超时则发送|0|
|`max.request.size`|发送的消息(序列化&压缩)大小的最大值，超过大小抛出 `RecordTooLargeException`|1048576(1M)|
|`compression.type`|消息的压缩算法，有 4 个可选值：none, gzip, snappy, lz4|none
|`request.timeout.ms`|等待消息发送请求响应的最长时间，超出后会重试|30000(30s)|
|`max.in.flight.request.per.connection`|每个连接最多缓存的请求数，大于 1 且有重试不保证消息有序|5|
|`connections.max.idle.ms`|连接最大空闲时长，超时后会关闭连接|540000(9m)|
|`receive.buffer.bytes`|Socket 接受消息缓冲区(`SO_RCVBUF`)的大小，-1 表示使用操作系统的默认值|32768(32K)|
|`send.buffer.bytes`|Socket 发送消息缓冲区(`SO_SNDBUF`)的大小，-1 表示使用操作系统的默认值|131072B(128KB)|
|`enable.idempotence`|设置是否开启幂等功能，用于事务消息|false|
|`transactional.id`|指定事务 id，必须唯一|null|