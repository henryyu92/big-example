### HLog
HBase 中系统故障恢复以及主从复制都是基于 HLog 实现。默认情况下，所有的写入操作(增加、更新和删除)的数据都先以追加形式写入 HLog，然后再写入 MemStore，当 RegionServer 异常导致 MemStore 中的数据没有 flush 到磁盘，此时需要回放 HLog 保证数据不丢失。此外，HBase 主从复制需要主集群将 HLog 日志发送给从集群，从集群在本地执行回放操作，完成集群之间的数据复制。

每个 RegionServer 默认拥有一个 HLog，1.1 版本后可以开启 MultiWAL 功能允许多个 HLog，每个 HLog 是多个 Region 共享的。HLog 中，日志单元 WALEntry 表示一次行级更新的最小追加单元，由 HLogKey 和 WALEdit 两部分组成，其中 HLogKey 由 tableName, regionName 以及 sequenceId 组成

HBase 中所有数据都存储在 HDFS 的指定目录(默认 /hbase)下，可以通过 hadoop 命令查看目录下与 HLog 有关的子目录：
```shell
hdfs dfs get /hbase
```
HLog 文件存储在 WALs 子目录下表示当前未过期的日志，同级子目录 oldWALs 表示已经过期的日志，WALs 子目录下通常有多个子目录，每个子目录代表一个 RegionServer，目录名称为 ```<domain>,<port>,<timestamp>```，子目录下存储对应 RegionServer 的所有 HLog 文件，通过 HBase 提供的 hlog 命令可以查看 HLog 中的内容：
```shell
./hbase hlog
```

HLog 文件生成之后并不会永久存储在系统中，HLog 整个生命周期包含 4 个阶段：
- HLog 构建：HBase 的任何写操作都会先将记录追加写入到 HLog 文件中
- HLog 滚动：HBase 后台启动一个线程，每隔一段时间(参数 ```hbase.regionserver.logroll.period``` 设置，默认 1 小时)进行日志滚动，日志滚动会新建一个新的日志文件，接收新的日志数据
- HLog 失效：写入数据一旦从 MemSotre 落盘到 HDFS 对应的日志数据就会失效。HBase 中日志失效删除总是以文件为单位执行，查看 HLog 文件是否失效只需要确认该 HLog 文件中所有日志记录对应的数据是否已经完成落盘，如果日志中所有记录已经落盘则可以认为该日志文件失效。一旦日志文件失效，就会从 WALs 文件夹移动到 oldWALs 文件夹，此时 HLog 文件并未删除
- HLog 删除：Master 后台会启动一个线程，每隔一段时间(参数 ```hbase.master.cleaner.interval``` 设置，默认 1 分钟)减产一次文件夹 oldWALs 下的所有失效日志文件，确认可以删除之后执行删除操作。确认失效可以删除由两个条件：
  - HLog 文件没有参与主从复制
  - HLog 文件在 oldWALs 文件夹中存在时间超过 ```hbase.master.logcleaner.ttl``` 设置的时长(默认 10 分钟)