### HLog

HLog 是 HBase 中 WAL (Write Ahead Log) 的实现，通常用于数据的容错和恢复。默认情况下，HLog 会记录下所有的数据变更到 HDFS，当 RegsionServer 发生异常时通过回放 HLog 可以恢复写入到 MemStore 但是还未刷盘到 HFile 的数据。

每个 RegionServer 只有一个 HLog，RegionServer 上的所有 Region 共用同一个 HLog。所有的写操作 (Put 和 Delete) 都会先追加到 HLog 然后再写入 MemStore。



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



#### WAL

Write Ahead Log(WAL)将所有对 HBase 中数据的更改记录到文件中。通常不需要 WAL，因为数据已经从 MemStore 移动到 StoreFile 上了，但是如果在 MemeStore 数据刷到 StoreFile 上之前 RegionServer 不可用，则 WAL 保证数据的更改能够重放。

通常一个 RegionServer 只有一个 WAL 实例，但是携带 hbase:meta 的 RegionServer 例外，meta 表有自己专用的 WAL。RegionServer 在将 Put 和 Delete 操作记录在 MemStore 之前会先记录在 WAL。

WAL 存放在 HDFS 的 /hbase/WALs 目录的每个 Region 子目录下。

##### WAL 分割

一个 RegionServer 上有多个 Region，所有的 Region 共享相同的 WAL 文件；WAL 文件中的每个 edit 保存着相关 Region 的信息，当一个 Region 是 opened 的时候，对应的 WAL edit 就需要重放，因此 WAL 文件中的 edit 必须按照 Region 分组以便可以重放特定的集合以重新生成特定 Region 中的数据；按照 Region 对 WAL edit 进行分组的过程称为日志分割。如果 RegionServer 出现故障这是恢复数据的关键过程。

日志拆分在集群启动期间由 HMaster 完成或者在 Region 服务器关闭的时候由 ServerShutdownHandler 完成。为了保证一致性，受影响的 Region 在数据恢复之前不可用；在指定 Region 再次可用之前需要恢复和重放所有的 WAL edit，因此在进程完成之前受日志拆分影响的 Region 将不可用。

日志拆分的过程：

- ```/hbase/WALs/<host>,<port>,<startcode>``` 目录重命名

  重命名目录很重要，因为即使 HMaster 认为它已经关闭，RegionServer 仍可能正在启动并接受请求。如果 RegionServer 没有立即响应并且没有和 ZooKeeper 保持会话心跳，则 HMaster 可能会将其解释为 RegionServer 故障。重命名日志目录可确保现有有效的 WAL 文件被意外写入
  新的目录名称格式如下：

  ```
  /hbase/WALs/<host>,<port>,<startcode>-splitting
  ```

- 分裂每个日志，一次一个

  日志分割器一次读取日志文件的一个 edit 到与之对应的缓冲区中，同时分割器启动了几个写线程把缓冲区中的 edit 内容写到一个临时的 edit 文件，命名方式为：

  ```
  /hbase/<table_name>/<region_id>/recoverd.edits/.temp
  ```

  此文件用于存储该 Region 中 WAL 日志中的所有 edit，日志拆分完成之后 .temp 文件将重命名为写入该文件的第一个日志的 ID。

  确定是否所有的 edit 已经写入可以将序列 ID 与写入 HFile 的最后一个 edit 进行比较，如果最后一个 edit 大于或等于文件名中包含的序列 ID，则很明显从 edit 文件的写已经完成。