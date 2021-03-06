## Elasticsearch

Elasticsearch 是一个高扩展性的分布式全文检索引擎，可以近乎实时的存储、检索数据，可以扩展到上百台服务器，处理 PB 级的数据。

Elasticsearch 采用 Lucene 作为核心来实现所有索引和搜索的功能并提供 RESTful API 来隐藏 Lucene 的复杂性，从而让全文搜索变得简单。

### 架构

![Elasticsearch 架构]()

### 集群

`Elasticsearch` 集群由一个或多个节点组成，集群由具有相同 `cluster.name` (默认为 elasticsearch) 的一个或多个节点组成，同一个集群内的节点名不能重复，但是集群名称一定要相同。

`Elasticsearch` 集群可以支持上百个节点，当节点设置了集群名之后就会自动加入集群，如果还没有则会自动创建集群。

#### 节点

节点是组成 `Elasticsearch` 集群的基本服务单元，每个节点都是一个 `Elasticsearch` 进程，同一个集群中的节点就有相同的集群名称(`cluster.name`， 默认为 `elasticsearch`)。

节点用于存储数据并参与集群的搜索，同一集群中的节点有唯一的名字，可以在配置文件中配置或者在启动时通过 `-E node.name=node_name` 指定。

`Elasticsearch` 集群为主从模型，每个节点可以有多个角色，同一个节点在不同的时刻可能会有不同的角色。

- **主节点(Master Node)**

  主节点负责管理集群元数据信息，当主节点故障时会从有资格被选举为 Master 的节点中选举出新的 Master，配置 `node.master:true` 使得节点具备被选举为 Master 的资格。

  为了减少主节点的负载，应该尽量使主节点的角色单一；

- **数据节点(Data Node)**
  数据节点负责保存数据、执行数据相关操作，数据节点对 CPU、内存、I/O 要求较高。配置 `node.data:true` 使得节点具备数据节点角色

- **预处理节点(Ingest Node)**

  预处理允许在索引文档之前通过事先定义好的处理器(`processor`)对数据进行处理，默认情况下所有的节点都是预处理节点，通过配置 `node.ingest:false` 可以禁用预处理。

- **协调节点(Coordinating Node)**

  客户端可以将请求发送到任意节点，接收到客户端请求的节点即为协调节点。协调节点将请求转发到文档所处的节点，并在处理结果返回后进行合并返回给客户端。

每个节点都保存了集群的状态，只有 `Master` 节点才能修改集群的状态信息，集群状态维护了集群中的必要信息，包括：

- 节点信息
- 索引以及相关的 `Mapping` 和 `Setting` 信息
- 分片的路由信息

Elasticsearch 的节点也可以分为  *数据节点*  和 *协调节点*，其中数据节点保存了分片的数据，而协调节点负责接收客户端的请求并将请求转发到合适的数据节点，在收到数据节点返回的结果后将结果聚合返回给客户端。

Elasticsearch 的每个节点都可以接受客户端的请求，因此所有的节点都可以承担协调节点的角色。

每个节点启动后，默认是一个 `Master eligible` 节点，可以参加选主流程，通过设置 `node.master: false` 可以禁止节点参与选主。

#### 集群状态

集群状态由 Master 节点负责维护，主节点接收到数据节点的状态更新则会将更新广播的集群的其他节点，这样集群中的每个节点都拥有整个集群的状态信息。



### 索引

索引是具有相同结构的文档集合；在系统上索引的名字全部小写，通过这个名字可以用来执行索引、搜索、更新和删除操作；一个集群中可以定义多个索引。

索引是指向主分片和副本分片的逻辑空间；Elasticseearch 会自动管理集群中的所有分片，当发生故障的时候 Elasticsearch 会把分片移动个到不同的节点或者添加新的节点。Elasticsearch 将索引分解成多个分片，当创建索引的时候可以定义分片的数量，每个分片本身是一个全功能的、独立的单元，可以托管在集群中的任何节点。

#### 文档

逻辑设计：一个索引中可以包含多个文档，elasticsearch 是面向文档的，索引和搜索的最小单位是文档，文档是 json 格式的。索引是文档的集合，索引被分片到多个节点上存储。

文档是 elasticsearch 中的最小数据单元，通常用 JSON 数据结构表示，每个索引下的类型中都可以存储多个文档；每个存储在索引中的文档都有一个类型和一个 ID。原始的 JSON 文档被存储在一个叫做 _source 的字段中。

#### 映射(Mapping)

映射用于定义文档中每个字段的类型，即所使用的 analyzer、是否索引等属相、是否关键等；每一个索引都有一个映射，映射可以事先定义也可以在第一次存储文档的时候自动识别。

### 分片

物理设计： elasticsearch 在后台把每个索引划分成多个分片，每个分片可以在集群中的不同服务器间迁移。elasticsearch 是以集群方式部署的，即使只有一个节点，集群的默认名称为 elasticsearch。

Elasticsearch 中的数据会切分为多个分片存储在不同的节点，用以解决数据水平扩展的问题。一个分片是一个运行的 Lucene 实例，主分片数在创建索引的时候指定，默认为 1，指定后不允许修改。

对文档的新建、索引和删除等写操作必须在主分片上完成之后才能被复制到相关的副本分片上。Elasticsearch 通过乐观锁解决由于并发导致的数据冲突问题，每个文档都有一个版本号 `version`，当文档被修改时版本号递增。



分片可以有一个或多个副本，用以解决数据高可用的问题。分片副本的数量可以动态调整，通过增加副本数可以在一定程度上增加服务的可用性。



分片需要提前做好容量规划，如果分片数设置过小则会导致后续无法通过增加节点实现水平扩展，且每个分片的数据量过大而增大数据重分配的耗时

- 主分片(Primary Shard)

  每个文档都存储在一个分片中，档存储一个文档的时候会首先存储在主分片中，然后复制到不同的副本中。默认情况下一个索引有 5 个分片，在创建索引的时候可以设定分片的数量，分片一旦建立则不能更改分片的数量。

- 副本分片(Replica Shard)

  每一个分片有零个或多个副本，副本主要是主分片的复制，有两个目的：

  - 增加高可用性：当主分片失败的时候，可以从副本分片中选择一个作为主分片
  - 提高性能：当查询的时候可以到主分片或者副本分片中进行查询

  默认情况下一个主分片有一个副本，当副本的数量可以动态的调整；副本分片必须部署在不同的节点且不能和主分片在相同的节点。

#### 路由(Routing)

当存储一个文档的时候，它会存储在唯一的主分片中，具体分片是通过散列值进行选择，默认情况下这个值由文档的 ID 生成；如果文档有一个指定的父文档，则从父文档 ID 中生成，该值可以在存储文档时进行修改。



### 安装

#### 目录结构

Elasticsearch 安装目录包含几个子目录，每个子目录存放不同的文件：

- bin 目录存放二进制脚本，包括 Elasticsearch 节点的启动和停止脚本
- config 目录存放 Elasticsearch 的配置文件
- jdk 目录下存放的是 Java 运行环境
- lib 目录下存放的 Elasticsearch 依赖的 jar 包
- logs 目录存放 Elasticsearch 运行时的日志文件
- modules 目录存放 Elasticsearch 的模块
- plugins 目录存放 Elasticsearch 的插件

#### 配置

Elasticsearch 的配置遵循  “约定大于配置” 的设计原则，配置文件在安装目录的 `config` 子目录中，包括 `elasticsearch.yml`, `jvm.options`, `log4j2.properties` 三个配置文件。