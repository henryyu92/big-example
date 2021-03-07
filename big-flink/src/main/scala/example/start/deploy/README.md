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

### Yarn Cluster 部署
Flink 应用提交到 Yarn 上支持两种模式
- Yarn Session 模式：Flink 向 Hadoop Yarn 申请足够多的资源，并在Yarn上启动长时间运行的Flink Session集群，用户可以通过RestAPI或Web页面将Flink任务提交到Flink Session集群上运行
- Single Job 模式：每个Flink任务单独向Yarn提交一个Application，并且每个任务都有自己的JobManager和TaskManager，当任务结束后对应的组件也会随任务释放

#### Yarn Session 模式
Yarn Session 模式其实是在Yarn上启动一个Flink Session集群，其中包括JobManager和TaskManager组件。Session集群会一直运行在Hadoop Yarn之上，底层对应的其实是Hadoop的一个Yarn Application应用。当Yarn SessionCluster启动后，用户就能够通过命令行或RestAPI等方式向Yarn Session集群中提交Flink任务，从而不需要再与Yarn进行交互，这样其实也是让Flink应用在相同的集群环境运行，从而屏蔽底层不同的运行环境。。

Flink默认使用YARN_CONF_DIR或者HADOOP_CONF_DIR环境变量获取Hadoop客户端配置文件。如果启动的节点中没有相应的环境变量和配置文件，则可能导致Flink启动过程无法正常连接到Hadoop Yarn集群。

通过yarn-session.sh命令启动Flink Yarn Session集群，集群启动完毕之后就可以在Yarn的任务管理页面查看Flink Session集群状况，并点击ApplicationMaster对应的URL，进入Flink Session Cluster集群中，注意在OnYARN的模式中，每次启动JobManager的地址和端口都不是固定的。
```shell script
# -n    配置启动的 Yarn Container
# -jm   配置 JobManager 的 JVM 内存大小
# -tm   配置 TaskManager 的 JVM 内存大小
# -s    配置集群中启动的 Slot 个数
./bin/yarn-session.sh -n 4 -jm 1024m -tm 4096m -s 16
```
通过以上方式启动Yarn Session集群，集群的运行与管理依赖于于本地YarnSession集群的本地启动进程，一旦进程关闭，则整个Session集群也会终止。此时可以通过，在启动Session过程中指定参数--d或--detached，将启动的Session集群交给Yarn集群管理，与本地进程脱离。通过这种方式启动Flink集群时，如果停止Flink Session Cluster，需要通过Yarn Application -kill [appid]来终止FlinkSession集群。

如果用户想将本地进程绑定到Yarn上已经提交的Session，可以通过以下命令Attach本地的进程到Yarn集群对应的Application，然后Yarn集群上ApplicationID对应的Session就能绑定到本地进程中。此时用户就能够对Session进行本地操作，包括执行停止命令等，例如执行Ctrl+C命令或者输入stop命令，就能将Flink Session Cluster停止。
```shell
./bin/yarn-session.sh -id [applicationid]
```
当Flink Yarn Session集群构建好之后，就可以向Session集群中提交Flink任务，可以通过命令行或者RestAPI的方式提交Flink应用到Session集群中。例如通过以下命令将Flink任务提交到Session中，正常情况下，用户就能够直接进入到Flink监控页面查看已经提交的Flink任务。
```shell
./bin/flink run ./windowsWordCountApp.jar
```

#### Single Job 模式
在Single Job模式中，Flink任务可以直接以单个应用提交到Yarn上，不需要使用Session集群提交任务，每次提交的Flink任务一个独立的Yarn Application，且在每个任务中都会有自己的JobManager和TaskManager组件，且应用所有的资源都独立使用，这种模式比较适合批处理应用，任务运行完便释放资源。

```shell script
# -m    指定 yarn-cluster
# -yn   指定 TaskManager 的数量
./bin/flink run -m yarn-cluster -yn 2 ./windowWordCountApp.jar
```

### Kubernetes Cluster 部署
Flink 支持 Kubernetes 部署模式，能够基于Kubernetes来构建Flink Session Cluster，也可以通过Docker镜像的方式向Kuernetes集群中提交独立的Flink任务。

在Kubernetes上构建Flink Session Cluster，需要将Flink集群中的组件对应的Docker镜像分别在Kubernetes集群中启动，其中包括JobManager、TaskManager、JobManager-Services三个镜像服务，其中每个镜像服务都可以从中央镜像仓库中获取，用户也可以构建本地的镜像仓库。

#### JobManager 配置

JobManager 对应的 yaml 文件中可以配置 JobManager 组件的参数
```yaml
apiVersion: extensions/v1beta1
kind: Deployment
metadata:
  name: flink-jobmanager
  spec:
    replicas: 1
    template:
      metadata:
        labels:
          app: flink
          component: jobmanager
      spec:
        containers:
          - name: jobmanager
            image: flink:latest
            args:
              - jobmanager
            ports:
              - containerPort: 6123
                name: rpc
              - containerPort: 6124
                name: blob
              - containerPort: 6125
                name: query
              - containerPort: 8081
                name: ui
            env:
              - name: JOB_MANAGER_RPC_ADDRESS
              - value: flink-jobmanager
```
TaskManager 对应的 yaml 配置文件配置 TaskMananger 组件的相关参数：
```yaml
apiVersion: extensions/v1beta1
kind: Deployment
metadata:
  name: flink-taskmanager
spec:
  replicas: 2
  templdate:
    labels:
      app: flink
      component: taskmanager
    spec:
      containers:
        - name: taskmanager
          image: flink:latest
          args:
            - taskmanager
          ports:
            - containerPort: 6121
              name: data
            - containerPort: 6122
              name: rpc
            - containerPort: 6125
              name: query
          env:
            - name: JOB_MANAGER_RPC_ADDRESS
              value: flink-jobmanager
```
JobManagerService 提供堆外的 RestApi 和 UI 地址，可以通过 Flink UI 的方式访问集群并获取任务和监控信息。
```yaml
apiVersion: v1
kind: Service
metadata:
  name: flink-jobmanager
spec:
  ports:
    - name: rpc
      port: 6123
    - name: blob
      port: 6124
    - name: query
      port: 6125
    - name: ui
      port: 8081
  selector:
    app: flink
    component: jobmanager
```
当各个组件服务配置文件定义完毕后，就可以通过使用以下Kubectl命令创建FlinkSession Cluster，集群启动完成后就可以通过JobJobManagerServices中配置的WebUI端口访问FlinkWeb页面。
```shell script
// 启动 jobmanager-service
kubectl create -f jobmanager-service.yaml

// 启动 jobmanager-deployment
kubectl create -f jobmanager-deployment.yaml

// 启动 taskmanager-deployment
kubectl create -f taskmanager-deployment.yaml
```
集群启动后就可以通过kubectl proxy方式访问Flink UI，需要保证kubectl proxy在终端中运行，并在浏览器里输入以下地址，就能够访问FlinkUI，其中JobManagerHost和port是在JobManagerYaml文件中配置的相应参数。
```
http://{JobManagerHost:Port}/api/v1/namespaces/default/services/fl ink-jobmanager:ui/proxy
```
可以通过kubectl delete命令行来停止Flink Session Cluster。
```shell script
// 停止 jobmanager-deployment
kubectl delete -f jobmanager-deployment.yaml

// 停止 taskmanager-deployment
kubectl delete -f taskmanager-deployment.yaml

// 停止 jobmanager-service
kubectl delete -f jobmanager-service.yaml
```