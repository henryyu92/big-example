### 控制器
Kafka 集群中会有一个或多个 broker，其中有一个 broker 会被选举为控制器(controller)，它负责管理整个集群中所有分区和副本的状态。当分区的 leader 副本故障时由控制器负责为该分区选举新的 leader 副本；当分区 ISR 集合发生变化时由控制器负责通知所有 broker 更新其元数据信息；当使用 ```kafka-topics.sh``` 为 topic 增加分区时也是由控制器负责分区的重新分配。
#### 控制器的选举和异常恢复
控制器的选举工作依赖于 ZooKeeper，成功选举为控制器的 broker 会在 ZooKeeper 中创建 ```/controller``` 临时节点，其中保存的内容为：
```json
{"version":1,"brokerid":0,"timestamp":""}
```
其中 brokerid 表示控制器对应的 broker 的 id，timestamp 表示选举为控制器的时间戳。

在任意时刻，集群中有且仅有一个控制器，每个 broker 在启动时会尝试去读取 /controller 节点下的 brokerid 的值，如果读取到的值不为 -1 则表示已经有其他 broker 节点成功选举为控制器，那么当前 broker 就会放弃尝试；如果 Zookeeper 中不存在 /controller 节点或者这个节点中的数据异常，那么就会尝试创建 /controller 节点，只有创建成功的节点才能成为控制器；每个 broker 都会在内存中保存当前控制器的 brokerid 的值。

ZooKeeper 中还有一个与控制器相关的 /controller_epoch 持久节点，节点中存放的是一个整型的 controller_epoch 值用于记录控制器发生变更的次数。controller_epoch 初始值为 1，当控制器发生变更时每新选出一个控制器就加 1。每个和控制器交互的请求都会携带 controller_epoch 字段，如果请求值小于控制器的 controller_epoch 则说明请求是向过期的控制器发送的那么这个请求会被认定为无效，如果请求值大于则说明已经有新的控制器当选。

控制器的 broker 具有更多的职责：
- 监听分区相关的变化。为 ZooKeeper 中的 /admin/reassign_partitions 节点注册 PartitionReassignmentHandler 处理分区重分配的动作；为 /isr_change_notification 节点注册 IsrChangeNotificationHandler 处理 ISR 集合变更的操作；为 /admin/preferred-replica-election 节点添加 PreferredReplicaElectionHandler 处理优先副本的选举动作
- 监听主题相关的变化。为 ZooKeeper 中的 /brokers/topics 节点添加 TopicChangeHandler 用来处理主题增减的变化；为 ZooKeeper 中的 /admin/delete_topics 节点添加 TopicDeletionHandler 用来处理删除主题的动作
- 监听 broker 相关变化。为 ZooKeeper 中的 /brokers/ids 节点添加 BrokerChangeHandler 处理 broker 的增减变化
- 从 ZooKeeper 中读取当前所有与主题、分区及 broker 有关的信息并进行相应的管理。对所有主题对应的 ```/broker/topics/<topic>``` 节点添加 PartitionModificationsHandler 监听主题中的分区分配变化
- 启动并管理分区状态机和副本状态机
- 更新集群的元数据信息
- 如果参数 ```auto.leader.rebalance.enable``` 参数设置为 true 则会启动一个名为 auto-leader-rebalance-task 的定时任务来负责维护分区的优先副本的均衡

控制器在选举成功后会读取 ZooKeeper 中各个节点的数据来初始化上下文(ControllerContext)信息，并且需要管理这些上下文信息。不管是监听器触发的事件或是其他事件都会读取或更新控制器中的上下文信息，Kafka 控制器使用单线程基于事件队列的模型，将每个事件做一层封装后按照事件发生的先后顺序暂存到 LinkedBlockingQueue 中，然后使用一个专门的线程(ControllerEventThread)按照 FIFO 顺序处理各个事件。

每个 broker 节点都会监听 /controller 节点，当数据发生变化时每个 broker 都会更新自身保存的 activeControllerId。如果 broker 控制器发生变更需要关闭相应的资源如关闭状态机、注销相应的监听器等。
#### 优雅关闭
使用 ```kill -s TERM $PIDS``` 可以优雅的关闭进程，Kafka 服务入口程序中有一个名为 kafka-shutdown-hock 的关闭钩子，待 Kafka 进程捕获终止信号时候会执行这个关闭钩子的内容，除了正常关闭一些必要的资源还会执行一个控制关闭(ControlledShutdonw)的动作，这个动作有两个优点：
- 可以让消息完全同步到磁盘上，在服务下次重新上线时不需要进行日志的恢复操作
- ControllerShutdown 在关闭服务之前会对其上的 leader 副本进行迁移这样就可以减少分区的不可用时间

若要成功执行 ControlledShutdown 动作还需要设置参数 ```controlled.shutdown.enable```为 true，默认是 true。ControlledShutdown 动作如果执行不成功还可以重试执行，这个重试的动作由参数 ```controlled.shutdown.max.retries``` 配置，默认为 3 次，每次重试的时间间隔由参数 ```controlled.shutdown.retry.backoff.ms``` 设置，默认 5000。

ControlleredShutdown 执行过程：
- 待关闭的 broker 在执行 ControlleredShutdown 动作时首先与 Kafka 控制器建立专用连接，然后发送 ControlledShutdownRequest 请求，该请求携带了 brokerId
- Kafka 控制器收到 ControlledShutdownRequest 后开始处理该 broker 上的所有分区副本。  
  如果分区副本数大于 1 且为 leader 副本则需要迁移 leader 副本并更新 ISR，具体的选举分配方案由 ControlledShutdownSelector 提供；如果分区副本只是大于 1，则控制器负责关闭这些副本；如果分区副本数只是 1 那么副本关闭动作会在整个 ControlledShutdown 动作执行之后由副本管理器来执行
  
对于分区副本数大于 1 且 leader 副本位于待关闭 broker 上的情况，如果在 Kafka 控制器处理之后 leader 副本还没有成功迁移，那么会将这些没有成功迁移 leader 副本的分区记录下来并且写入 ControlledShutdownResponse，待关闭的 broker 在收到 ControlledShutdownResponse 之后需要判断整个 ControlledShutdown 动作是否执行成功，以此来进行可能的重试或继续执行接下来的关闭资源动作

自定义 ControlledShutdown 过程，在 KafkaAdminClient 中添加方法：
```java
public abstract ControlledShutdownResult controlledShutdown(Node node, final ControlledShutdownOptions options);

public ControlledShutdownResult controlledShutdown(Node node){
    return controlledShutdown(node, new ControlledShutdownOptions());
}


@InterfaceStability.Evolving
public class ControlledShutdownOptions extends AbstractOptions<ControlledShutdownOptions>{

}

@InterfaceStability.Evoling
public class ControlledShutdownResult{
    private final KafkaFuture<ControoledShutdownResponse> future;
    public ControlledShutdownResult(KafkaFuture<ControlledShutdownResponse> future){
        this.future = future;
    }
    public KafkaFuture<ControlledShutdownResponse> values(){
        return future;
    }
}

public ControlledShutdownResult controlledShutdown(Node node, final ControlledShutdownOptions options){
    final KafkaFutureImpl<ControlledShutdownResponse> future = new KafkaFutureImpl();
	final Long now = time.milliseconds();
	
	runnable.call(new Call("controlledShutdown", calcDeadlineMs(now, options.timeoutMs()), new ControllerNodeProvider()){
	    AbstractRequest.Builder createRequest(int timeoutMs){
		    int nodeId = node.id();
			if(nodeId < 0){
			    List<Node> nodes = metadata.fetch().nodes();
				for(Node nodeItem : nodes){
				    if(nodeItem.host().equals(node.host()) && nodeItem.port() == node.port()){
					    nodeId = nodeItem.id();
						break;
					}
				}
			}
			return new ControlledShutdownRequest.Builder(nodeId, ApiKeys.CONTROLLED_SHUTDOWN.latestVersion());
		}
		
		void handleResponse(AbstractResponse abstractResponse){
		    ControlledShutdownResponse response = (ControlledShutdonwResponse) abstractResponse;
			future.complete(response);
		}
		
		void handleFailure(Throwable throwable){
		    future.completeExceptionally(throwable);
		}
	}, now);
	return new ControlledShutdownRequest(future);
}
```
#### 分区 leader 选举
分区 leader 副本的选举由控制器负责具体实施。当创建分区或分区上线的时候都需要执行 leader 的选举，对应的选举策略为 OfflinePartitionLeaderElectionStrategy。这种策略的基本思想是按照 AR 集合中副本的顺序查找第一个存活的副本，并且这个副本在 ISR 集合中。一个分区的 AR 集合在分配的时候就被指定，并且只要不发生重分配的情况，集合内部副本的顺序是保持不变的，而分区的 ISR 集合中副本的顺序可能会改变。

如果 ISR 中美欧可用的副本，那么如果 ```unclean.leader.election.enable``` 参数设置为 true(默认是 false)则表示允许从非 ISR 列表中选举 leader，即从 AR 集合中找到第一个存活的副本即为 leader。

当分区重分配时也需要执行 leader 的选举动作，对应的策略为 ReassignPartitionLeaderElectionStrategy。

当发生优先副本选举时，直接将优先副本设置为 leader 即可，AR 集合中的第一个副本即为优先副本(PreferredReplicaPartitionLeaderElectionStategy)

当某个节点被优雅关闭时，位于这个节点的 leader 副本会下线，对应的分区需要执行 leader 的选举，对应的选举策略为 ControlledShutdownPartitonLeaderElectionStrategy：从 AR 列表中找到第一个存活的副本，且这个副本在目前的 ISR 列表中，与此同时还要确保这个副本不处于正在被关闭的节点上。

#### broker 参数
##### broker.id
broker.id 是 broker 在启动之前必须设定的参数之一，在 Kafka 集群中，每个 broker 都有唯一的 id 值用于区分彼此。broker 在启动时会在 ZooKeeper 中的 /brokers/ids 路径下创建一个以当前 brokerId 为名称的虚节点，broker 的健康状态检查就依赖于此虚拟节点。当 broker 下线时，该虚节点会自动删除，其他 broker 节点或客户端通过判断 /brokers/ids 路径下是否有此 broker 的 brokerId 节点来确定该 broker 的健康状态。

可以通过 broker 端的配置文件 config/server.properties 里的 broker.id 参数来配置 brokerId，默认情况下 broker.id 的值为 -1。在 Kafka 中 brokerId 的值必须大于等于 0 才有可能正常启动，还可以通过 meta.properties 文件自动生成 brokerId。 meta.properties 文件在 broker 成功启动之后在每个日志根目录都会生成，与 broker.id 的关联如下：
- 如果 log.dir 或者 log.dirs 中配置了多个日志根目录，这些日志根目录中的 meta.properties 文件配置的 broker.id 不一致则会抛出 InconsistentBrokerIdException 的异常
- 如果 config/server.properties 配置文件里配置的 broker.id 的值和 meta.properteis 文件里的 broker.id 值不一致，那么同样会抛出 InconsistentBrokerIdException 的异常
- 如果 config/server.properties 配置文件中并未配置 broker.id 的值，那么就以 meta.properties 文件中的 broker.id 值为准
- 如果没有 meta.properties 文件，那么在获取合适的 broker.id 值之后会创建一个新的 meta.properties 文件并将 broker.id 值存入其中

如果 config/server.properteis 配置文件中没有配置 broker.id 并且日志根目录中也没有任何 meta.properties 文件，Kafka 提供了 broker 端参数 ```broker.id.generation.enable``` 和 ```reserved.broker.max.id``` 生成新的 brokerId。

```broker.id.generation.enable``` 参数用来配置是否开启自动生成 brokerId 的功能，默认情况是 true。自动生成的 brokerId 有一个基准值，自动生成的 brokerId 必须超过这个基准值，这个基准值由参数 ```reserved.broker.max.id``` 配置，默认是 1000，也就是说默认情况下自动生成的 brokerId 是从 1001 开始的。

自动生成 brokerId 的原理是先往 ZooKeeper 中的 /brokers/seqid 节点中写入一个空字符串，然后获取返回的 Stat 信息中的 version 值，进而将 version 的值和 reserved.broker.max.id 参数配置的值相加。先往节点中写入数据再获取 Stat 信息可以确保返回的 version 值大于 0 进而可以确保生成的 brokerId 值大于 reserved.broker.max.id 参数配置的值。

##### bootstrap.servers
```bootstrap.servers``` 参数指定 broker 的地址列表，用来发现 Kafka 集群元数据信息的服务地址。客户端连接 Kafka 集群需要经历 3 个过程：
- 客户端与 ```bootstrap.servers``` 参数所指定的 Server 连接，并发送 MetadataRequest 请求来获取集群的元数据信息
- Server 在收到 MetadataRequest 请求之后，返回 MetadataResponse 给客户端，MetadataResponse 中包含了集群的元数据信息
- 客户端在收到 MetadataResponse 之后解析出其中包含的源数据信息，然后与集群中的每个节点建立连接，之后就可以发送消息了

可以将 Server 角色和 Kafka 集群角色分开，实现自定义的路由、负载均衡等功能。

### broker 其他参数
|参数|默认值|含义|
|-|-|-|
|auto.create.topic.enable|true|是否开启自动创建主题的功能|
|auto.leader.relablance.enable|true|是否开启自动 leader 再均衡的功能|
|background.threads|10|执行后台任务的线程数|
|compression.type|producer|消息压缩类型|
|delete.topic.enable|true|是否可以删除主题|
|leader.imbalance.check.interval.seconds|300|检查 leader 是否分布不均衡的周期|
|leader.imbalance.broker.percentage|10|允许 leader 不均衡的比例，超过则会触发 leader 再均衡|
|log.flush.interval.message|Long.MAX_VALUE|日志文件中消息存入磁盘的阈值|
|log.flush.interval.ms||刷新日志文件的时间间隔，如果不配置则依据 log.flush.scheduler.interval.ms 值|
|log.flush.scheduler.interval.ms|Long.MAX_VALUE|检查日志文件是否需要刷新的时间间隔|
|log.retention.bytes|-1|日志文件的最大保留大小|
|log.retention.hours|168(7天)|日志存留时间，优先级最低|
|log.retention.minutes||和 log.retention.hours 一样，优先级居中|
|log.retention.ms||和 log.retention.hours 一样，优先级最高|
|log.roll.hours|168(7天)|经过多长时间后强制创建日志分段|
|log.roll.ms||和 log.roll.hours 一样，优先级较高|
|log.segment.bytes|1073741824(1G)|日志分段文件的最大值，超过这个值会强制创建一个新的日志分段|
|log.segment.delete.delay.ms|60000|从操作系统删除文件前的等待时间|
|min.insync.replicas|1|ISR 中最少的副本数|
|num.io.threads|8|处理请求的线程数|
|num.network.threads|3|处理接收和返回响应的线程数|
|log.cleaner.enable|true|是否开启日志清理功能|
|log.cleaner.min.cleanable.ratio|0.5|限定可执行清理操作的最小污浊率|
|log.cleaner.threads|1|用于日志清理的后台线程数|
|log.cleanup.policy|delete|日志清理策略|
|log.index.interval.bytes|4096|每隔多少个字节的消息量写入就添加一条索引|
|log.index.size.max.bytes|10485760(10M)|索引文件的最大值|
|log.message.format.version|2.0-IVI|消息格式中的版本|
|log.message.timestamp.type|CreateTime|消息中的时间戳类型|
|log.retention.check.interval.ms|300000|日志清理的检查周期|
|num.partitions|1|创建主题时的默认分区数|
|reserved.broker.max.id|1000|自动创建 broker.id 时保留的最大值，即自动创建 broker.id 时的起始值为 reserved.broker.max.id+1|
|create.topic.policy.class.name||创建主题时用来验证合法性的策略|
|broker.id.generation.enable|true|是否开启自动生成 broker.id 功能|
|broker.rack||配置 broker 的机架信息|

**[Back](../)**