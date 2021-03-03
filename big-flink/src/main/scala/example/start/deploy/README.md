## 集群部署


### Standalone Cluster 部署

Standalone 集群需要在节点的 master 文件中配置 JobManager 的 ip 和端口，在 conf/slaves 文件中配置 TaskManager 的 ip 和端口。

在 conf/flink-conf.yaml 文件中可以配置 Flink 集群中的 JobManager 和 TaskManager 组件的优化配置项：
- jobmanager.rpc.address：Flink Cluster集群的JobManager RPC通信地址，一般需要配置指定的JobManager的IP地址
- jobmanager.heap.mb：对JobManager的JVM堆内存大小进行配置，默认为1024M
- taskmanager.heap.mb：对TaskManager的JVM堆内存大小进行配置，默认为1024M
- taskmanager.numberOfTaskSlots：配置每个TaskManager能够贡献出来的Slot数量，根据TaskManager所在的机器能够提供给Flink的CPU数量决定
- parallelism.default：Flink任务默认并行度，与整个集群的CPU数量有关，增加parallelism可以提高任务并行的计算的实例数，提升数据处理效率，但也会占用更多Slot
- taskmanager.tmp.dirs：集群临时文件夹地址，Flink会将中间计算数据放置在相应路径中

默认配置可以在提交 Job 的时候通过 -D 选项来动态的调整，使用参数指定后会覆盖默认值。

Flink Standalone集群配置完成后，然后在Master节点，通过bin/start-cluster.sh脚本直接启动Flink集群，Flink会自动通过Scp的方式将安装包和配置同步到Slaves节点。启动过程中如果未出现异常，就表示Flink Standalone Cluster启动成功，可以通过https://{JopManagerHost:Port} 访问Flink集群并提交任务，其中JopManagerHost和Port是前面配置JopMa

#nager的IP和端口。

对于已经启动的集群，可以动态地添加或删除集群中的JobManager和TaskManager，该操作通过在集群节点上执行jobmanager.sh和taskmanager.sh脚本来完成。

```shell
# 添加或删除 JobManager
job/jobmanager.sh ((start|start-foreground) [host] [webui-port]) | stop | stop-all

# 向集群中添加 TaskMananger
bin/taskmanager.sh start | start-foreground | stop | stop-all
```

