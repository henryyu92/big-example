### Elasticsearch

Elasticsearch 是一个高扩展性的分布式全文检索引擎，可以近乎实时的存储、检索数据，可以扩展到上百台服务器，处理 PB 级的数据。

Elasticsearch 采用 Lucene 作为核心来实现所有索引和搜索的功能并提供 RESTful API 来隐藏 Lucene 的复杂性，从而让全文搜索变得简单。

#### 集群



#### 节点

节点时一个 Elasticsearch 实例，其本质是一个 Java 进程，一台机器上可以运行多个 Elasticsearch 实例。

每个节点都有一个唯一的名字，可以通过配置文件设置，也可以在节点启动时通过 `-E node.name=node_name` 来指定。

每个节点在启动之后，会分配一个 UID，保存在 data 目录下。



每个节点启动后，默认是一个 `Master eligible` 节点，可以参加选主流程，通过设置 `node.master: false` 可以禁止节点参与选主。

每个节点都保存了集群的状态，只有 `Master` 节点才能修改集群的状态信息，集群状态维护了集群中的必要信息，包括：

- 节点信息
- 索引以及相关的 `Mapping` 和 `Setting` 信息
- 分片的路由信息

Elasticsearch 的节点也可以分为  *数据节点*  和 *协调节点*，其中数据节点保存了分片的数据，而协调节点负责接收客户端的请求并将请求转发到合适的数据节点，在收到数据节点返回的结果后将结果聚合返回给客户端。

Elasticsearch 的每个节点都可以接受客户端的请求，因此所有的节点都可以承担协调节点的角色。



Elasticsearch 中的节点有多种类型：

```yml
node:
  master: true	// Master 节点
  data: true	// 数据节点
```



#### 集群

集群由具有相同 `cluster.name` (默认为 elasticsearch) 的一个或多个节点组成，同一个集群内的节点名不能重复，但是集群名称一定要相同。

#### 分片

Elasticsearch 中的数据会切分为多个分片存储在不同的节点，用以解决数据水平扩展的问题。一个分片是一个运行的 Lucene 实例，主分片数在创建索引的时候指定，默认为 1，指定后不允许修改。

对文档的新建、索引和删除等写操作必须在主分片上完成之后才能被复制到相关的副本分片上。Elasticsearch 通过乐观锁解决由于并发导致的数据冲突问题，每个文档都有一个版本号 `version`，当文档被修改时版本号递增。



分片可以有一个或多个副本，用以解决数据高可用的问题。分片副本的数量可以动态调整，通过增加副本数可以在一定程度上增加服务的可用性。



分片需要提前做好容量规划，如果分片数设置过小则会导致后续无法通过增加节点实现水平扩展，且每个分片的数据量过大而增大数据重分配的耗时

#### 索引

#### 文档

物理设计： elasticsearch 在后台把每个索引划分成多个分片，每个分片可以在集群中的不同服务器间迁移。elasticsearch 是以集群方式部署的，即使只有一个节点，集群的默认名称为 elasticsearch。

逻辑设计：一个索引中可以包含多个文档，elasticsearch 是面向文档的，索引和搜索的最小单位是文档，文档是 json 格式的。索引是文档的集合，索引被分片到多个节点上存储。


### Rest

```
put  localhost:9200/索引/_doc/id        创建指定 id 文档
post localhost:9200/索引/_doc           创建随机 id 文档
post localhost:9200/索引/_update/id     修改文档
delete localhost:9200/索引/id           删除指定 id 文档
get  localhost:9200/索引/id             查询指定 id 文档
post localhost:9200/索引/_search        查询所有数据
```

### Elasticsearch 安装

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