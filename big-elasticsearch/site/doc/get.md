## 读流程

ES的读取分为GET和Search两种操作，这两种读取操作有较大的差异，GET/MGET必须指定三元组：_index、_type、_id。也就是说，根据文档id从正排索引中获取内容。而Search不指定_id，根据关键词从倒排索引中获取内容。

搜索和读取文档都属于读操作，可以从主分片或副分片中读取数据。从主分片或副分片中读取时的步骤：

1. 客户端向NODE1发送读请求
2. NODE1使用文档ID来确定文档属于分片0，通过集群状态中的内容路由表信息获知分片0有三个副本数据，位于所有的三个节点中，此时它可以将请求发送到任意节点，这里它将请求转发到NODE2
3. NODE2将文档返回给 NODE1,NODE1将文档返回给客户端

NODE1作为协调节点，会将客户端请求轮询发送到集群的所有副本来实现负载均衡。

在读取时，文档可能已经存在于主分片上，但还没有复制到副分片。在这种情况下，读请求命中副分片时可能会报告文档不存在，但是命中主分片可能成功返回文档。一旦写请求成功返回给客户端，则意味着文档在主分片和副分片都是可用的。

### 协调节点流程

协调节点流程执行的线程池为 `http_server_worker`。

TransportSingleShardAction 类用来处理存在于一个单个（主或副）分片上的读请求。将请求转发到目标节点，如果请求执行失败，则尝试转发到其他节点读取。

- 内容路由
  - 在TransportSingleShardAction.AsyncSingleAction构造函数中，准备集群状态、节点列表等信息
  - 根据内容路由算法计算目标shardid，也就是文档应该落在哪个分片上
  - 计算出目标shardid后，结合请求参数中指定的优先级和集群状态确定目标节点，由于分片可能存在多个副本，因此计算出的是一个列表
- 转发请求：作为协调节点，向目标节点转发请求，或者目标是本地节点，直接读取数据。发送函数声明了如何对Response进行处理：AsyncSingleAction类中声明对Response进行处理的函数。无论请求在本节点处理还是发送到其他节点，均对Response执行相同的处理逻辑
  - 在TransportService::sendRequest中检查目标是否是本地node
  - 如果是本地node，则进入TransportService#sendLocalRequest流程，sendLocalRequest不发送到网络，直接根据action获取注册的reg，执行processMessageReceived
  - 如果发送到网络，则请求被异步发送，“sendRequest”的时候注册handle，等待处理Response，直到超时
  - 等待数据节点的回复，如果数据节点处理成功，则返回给客户端；如果数据节点处理失败，则进行重试

### 数据节点流程

数据节点执行流程的线程池为 get

数据节点接收协调节点请求的入口为：TransportSingleShardAction.ShardTransportHandler#messageReceived。读取数据并组织成Response，给客户端channel返回。shardOperation先检查是否需要refresh，然后调用indexShard.getService（）.get（）读取数据并存储到GetResult中。

- 读取及过滤：在ShardGetService#get（）函数中，调用GetResult getResult = innerGet（）；获取结果。GetResult 类用于存储读取的真实数据内容。核心的数据读取实现在ShardGetService#innerGet（）函数中。
  - 通过 indexShard.get（）获取 Engine.GetResult。Engine.GetResult 类与innerGet 返回的GetResult是同名的类，但实现不同。indexShard.get（）最终调用InternalEngine#get读取数据
  - 调用ShardGetService#innerGetLoadFromStoredFields（），根据type、id、DocumentMapper等信息从刚刚获取的信息中获取数据，对指定的 field、source 进行过滤（source 过滤只支持对字段），把结果存于GetResult对象中
- InternalEngine的读取过程：InternalEngine#get过程会加读锁。处理realtime选项，如果为true，则先判断是否有数据可以刷盘，然后调用Searcher进行读取。Searcher是对IndexSearcher的封装。



### MGET 流程

MGET 的主要处理类：TransportMultiGetAction，通过封装单个 GET 请求实现。

- 遍历请求，计算出每个doc的路由信息，得到由shardid为key组成的requestmap。这个过程没有在TransportSingleShardAction中实现，是因为如果在那里实现，shardid就会重复，这也是合并为基于分片的请求的过程
- 循环处理组织好的每个 shard 级请求，调用处理 GET 请求时使用TransportSingleShardAction#AsyncSingleAction处理单个doc的流程
- 收集Response，全部Response返回后执行finishHim（），给客户端返回结果

回复的消息中文档顺序与请求的顺序一致。如果部分文档读取失败，则不影响其他结果，检索失败的doc会在回复信息中标出



### Serarch 流程

GET操作只能对单个文档进行处理，由_index、_type和_id三元组来确定唯一文档。但搜索需要一种更复杂的模型，因为不知道查询会命中哪些文档。

找到匹配文档仅仅完成了搜索流程的一半，因为多分片中的结果必须组合成单个排序列表。集群的任意节点都可以接收搜索请求，接收客户端请求的节点称为协调节点。在协调节点，搜索任务被执行成一个两阶段过程，即query then fetch。真正执行搜索任务的节点称为数据节点。

需要两个阶段才能完成搜索的原因是，在查询的时候不知道文档位于哪个分片，因此索引的所有分片（某个副本）都要参与搜索，然后协调节点将结果合并，再根据文档ID获取文档内容。例如，有5个分片，查询返回前10个匹配度最高的文档，那么每个分片都查询出当前分片的TOP 10，协调节点将5×10 = 50的结果再次排序，返回最终TOP 10的结果给客户端。



ES中的数据可以分为两类：精确值和全文，精确值，比如日期和用户id、IP地址等，全文，指文本内容，比如一条日志，或者邮件的内容。这两种类型的数据在查询时是不同的：对精确值的比较是二进制的，查询要么匹配，要么不匹配；全文内容的查询无法给出“有”还是“没有”的结果，它只能找到结果是“看起来像”你要查询的东西，因此把查询结果按相似度排序，评分越高，相似度越大。



#### 建立索引

如果是全文数据，则对文本内容进行分析，这项工作在 ES 中由分析器实现。分析器实现如下功能：

- 字符过滤器。主要是对字符串进行预处理，例如，去掉HTML，将&转换成and等。
- 分词器（Tokenizer）。将字符串分割为单个词条，例如，根据空格和标点符号分割，输出的词条称为词元（Token）。
- Token过滤器。根据停止词（Stop word）删除词元，例如，and、the等无用词，或者根据同义词表增加词条，例如，jump和leap。
- 语言处理。对上一步得到的Token做一些和语言相关的处理，例如，转为小写，以及将单词转换为词根的形式。语言处理组件输出的结果称为词（Term）。

分析完毕后，将分析器输出的词（Term）传递给索引组件，生成倒排和正排索引，再存储到文件系统中。

####  执行搜索

搜索调用Lucene完成，如果是全文检索，则：

- 对检索字段使用建立索引时相同的分析器进行分析，产生Token列表；
- 根据查询语句的语法规则转换成一棵语法树；
- 查找符合语法树的文档；
- 对匹配到的文档列表进行相关性评分，评分策略一般使用TF/IDF；
- 根据评分结果进行排序。

ES目前有两种搜索类型：DFS_QUERY_THEN_FETCH；QUERY_THEN_FETCH（默认）。两种不同的搜索类型的区别在于查询阶段，DFS查询阶段的流程要多一些，它使用全局信息来获取更准确的评分。



一个搜索请求必须询问请求的索引中所有分片的某个副本来进行匹配。假设一个索引有5个主分片，每个主分片有1个副分片，共10个分片，一次搜索请求会由5个分片来共同完成，它们可能是主分片，也可能是副分片。也就是说，一次搜索请求只会命中所有分片副本中的一个。



#### 协调节点流程

两阶段相应的实现位置：查询（Query）阶段—search.InitialSearchPhase；取回（Fetch）阶段—search.FetchSearchPhase。

- Query阶段：在初始查询阶段，查询会广播到索引中每一个分片副本（主分片或副分片）。每个分片在本地执行搜索并构建一个匹配文档的优先队列。优先队列是一个存有topN匹配文档的有序列表。优先队列大小为分页参数from +size。QUERY_THEN_FETCH搜索类型的查询阶段步骤如下：
  - 客户端发送search请求到NODE 3。
  - Node 3将查询请求转发到索引的每个主分片或副分片中。
  - 每个分片在本地执行查询，并使用本地的Term/Document Frequency信息进行打分，添加结果到大小为from + size的本地有序优先队列中。
  - 每个分片返回各自优先队列中所有文档的ID和排序值给协调节点，协调节点合并这些值到自己的优先队列中，产生一个全局排序后的列表。

协调节点广播查询请求到所有相关分片时，可以是主分片或副分片，协调节点将在之后的请求中轮询所有的分片副本来分摊负载。查询阶段并不会对搜索请求的内容进行解析，无论搜索什么内容，只看本次搜索需要命中哪些shard，然后针对每个特定shard选择一个副本，转发搜索请求。

Query 阶段流程的线程池：http_server_work。

1. 解析请求在RestSearchAction#prepareRequest方法中将请求体解析为SearchRequest数据结构
2. 构造目的shard列表将请求涉及的本集群shard列表和远程集群的shard列表（远程集群用于跨集群访问）合并
3. 遍历所有shard发送请求请求是基于shard遍历的，如果列表中有N个shard位于同一个节点，则向其发送N次请求，并不会把请求合并为一个。shardsIts为本次搜索涉及的所有分片，shardRoutings.nextOrNull（）从某个分片的所有副本中选择一个，例如，从website中选择主分片。转发请求同时定义一个Listener，用于处理Response
4. 收集返回结果本过程在search线程池中执行。onShardSuccess对收集到的结果进行合并。successfulShardExecution方法检查是否所有请求都已收到回复，是否进入下一阶段。onPhaseDone会调用executeNextPhase，从而开始执行取回阶段



Query阶段知道了要取哪些数据，但是并没有取具体的数据，这就是Fetch阶段要做的。Fetch阶段由以下步骤构成：

- 协调节点向相关NODE发送GET请求
- 分片所在节点向协调节点返回数据
- 协调节点等待所有文档被取得，然后返回给客户端

分片所在节点在返回文档数据时，处理有可能出现的_source字段和高亮参数。协调节点首先决定哪些文档“确实”需要被取回，例如，如果查询指定了{＂from＂: 90, ＂size＂:10 }，则只有从第91个开始的10个结果需要被取回。为了避免在协调节点中创建的number_of_shards * （from + size）优先队列过大，应尽量控制分页深度。



Fetch阶段的目的是通过文档ID获取完整的文档内容。执行本流程的线程池：search。

- 发送Fetch请求。Query阶段的executeNextPhase方法触发Fetch阶段，Fetch阶段的起点为FetchSearchPhase# innerRun函数，从查询阶段的shard列表中遍历，跳过查询结果为空的shard，对特定目标shard执行executeFetch来获取数据，其中包括分页信息。对scroll请求的处理也在FetchSearchPhase# innerRun函数中。executeFetch的参数querySearchResult中包含分页信息，最后定义一个Listener，每成功获取一个shard数据后就执行counter.onResult，其中调用对结果的处理回调，把result保存到数组中，然后执行countDown。
- 收集结果。收集器的定义在innerRun中，包括收到的shard数据存放在哪里，收集完成后谁来处理。fetchResults 用于存储从某个 shard 收集到的结果，每收到一个 shard 的数据就执行一次counter.countDown（）。当所有shard数据收集完毕后，countDown会出触发执行finishPhase。moveToNextPhase方法执行下一阶段，下一阶段要执行的任务定义在FetchSearchPhase构造函数中，主要是触发ExpandSearchPhase。
- ExpandSearchPhase。取回阶段完成之后执行ExpandSearchPhase#run，主要判断是否启用字段折叠，根据需要实现字段折叠功能，如果没有实现字段折叠，则直接返回给客户端。
- 回复客户端。ExpandSearchPhase执行完之后回复客户端，在sendResponsePhase方法中实现。

#### 数据节点流程

执行搜索的数据节点流程的线程池：search。