# Put

HBase 是一种对写操作友好的系统，为了适应不同数据量的写场景，HBase 提供了 3 种数据写入的 API：
- ```put(Put)```：单行数据写入 API，服务端先写 WAL 然后写入 MemStore。默认每次写入都需要执行一次 RPC 和磁盘持久化操作，写入吞吐量受限于网络带宽以及 flush 的速度，但是由于每次写操作都能持久化到磁盘，因此不会有数据丢失
- ```put(List<Put>)```：批量数据写入 API，客户端先缓存 put 当数量到达阈值后发起 RPC 写入请求，服务端一次性写入 WAL 和 MemStore。批量写入减少了 RPC 以及 flush 带来的开销，但是批量写入会由于 put 写往不同的 RegionServer 时不能保证数据写入的原子性，即可能出现部分写成功部分写失败，失败的部分需要重试
- ```bulkLoad```：HBase 提供了将数据生成 HFile 然后直接加载到对应的 Region 下的 Column Family 内，在生成 HFile 时服务端不会有任何 RPC 调用，只有在 load HFile 时会调用 RPC。bulkLoad 是一种完全离线的快速批量写入方案，不会对集群产生巨大压力

每次 Put 操作都会创建一个新版本的 Cell，默认情况下系统使用 ```currentTimeMillis```，可以在 Put 的时候指定版本，但是系统使用时间戳作为版本为了计算 TTL，因此最好不要自行设置版本。

```java

```