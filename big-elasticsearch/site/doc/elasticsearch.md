## 核心概念
Elasticsearch 是一个开源的高扩展(可扩展至上百台服务器，处理 PB 级别的数据)的分布式全文检索引擎，它可以近乎实时的存储、检索数据。Elasticsearch 采用 Lucene 作为核心来实现所有索引和搜索的功能并提供 RESTful API 来隐藏 Lucene 的复杂性，从而让全文搜索变得简单。
### 索引词(Term)
在 Elasticsearch 中，索引词是一个能够被索引的精确值，如 Foo，索引词是可以通过 term 查询进行精确的搜索。
### 文本(Text)
文本是一段普通的非结构化文字，通常文本会被分析成一个个的索引词存储在 Elasticsearch 的索引库中。为了能让文本能够进行搜索，文本字段需要事先进行分析，当对文本中的关键字进行查询的时候，搜索引擎应该根据搜索条件搜索出原文本。
### 分析(analysis)
分析是将文本转换为索引词的过程，分析的结果依赖于分词器。
### 集群(Cluster)
集群由一个或多个节点组成，对外提供索引和搜索功能；一个集群有一个唯一的名称，默认为 “Elasticsearch”，每个节点只能是一个集群的一部分，当节点被设置成为相同额集群名时就自动组成同一个集群。当需要有多个集群的时候，要确保每个集群的名字不同，否则节点可能加入错误的集群。
### 节点(Node)
一个节点是一个逻辑上独立的服务，它是集群的一部分，可以存储数据并参与集群的索引和搜索功能；节点也有唯一的名字，在启动的时候分配，在网络中 Elasticsearch 集群通过节点名称进行管理和通信。节点在启动的时候会加入集群，默认是名为 Elasticsearch 的集群，如果启动时集群不存在则会创建集群。
### 路由(Routing)
当存储一个文档的时候，它会存储在唯一的主分片中，具体分片是通过散列值进行选择，默认情况下这个值由文档的 ID 生成；如果文档有一个指定的父文档，则从父文档 ID 中生成，该值可以在存储文档时进行修改。
### 分片(Shard)
索引是指向主分片和副本分片的逻辑空间；Elasticseearch 会自动管理集群中的所有分片，当发生故障的时候 Elasticsearch 会把分片移动个到不同的节点或者添加新的节点。Elasticsearch 将索引分解成多个分片，当创建索引的时候可以定义分片的数量，每个分片本身是一个全功能的、独立的单元，可以托管在集群中的任何节点。
### 主分片(Primary Shard)
每个文档都存储在一个分片中，档存储一个文档的时候会首先存储在主分片中，然后复制到不同的副本中。默认情况下一个索引有 5 个分片，在创建索引的时候可以设定分片的数量，分片一旦建立则不能更改分片的数量。
### 副本分片(Replica Shard)
每一个分片有零个或多个副本，副本主要是主分片的复制，有两个目的：
- 增加高可用性：当主分片失败的时候，可以从副本分片中选择一个作为主分片
- 提高性能：当查询的时候可以到主分片或者副本分片中进行查询

默认情况下一个主分片有一个副本，当副本的数量可以动态的调整；副本分片必须部署在不同的节点且不能和主分片在相同的节点。
### 索引(Index)
索引是具有相同结构的文档集合；在系统上索引的名字全部小写，通过这个名字可以用来执行索引、搜索、更新和删除操作；一个集群中可以定义多个索引。
### 类型(Type)
每个索引里可以有一个或多个类型，类型是索引中的一个逻辑分组，同一个类型下的文档都具有相同的字段。
### 文档(Document)
文档是 elasticsearch 中的最小数据单元，通常用 JSON 数据结构表示，每个索引下的类型中都可以存储多个文档；每个存储在索引中的文档都有一个类型和一个 ID。原始的 JSON 文档被存储在一个叫做 _source 的字段中。
### 映射(Mapping)
映射用于定义文档中每个字段的类型，即所使用的 analyzer、是否索引等属相、是否关键等；每一个索引都有一个映射，映射可以事先定义也可以在第一次存储文档的时候自动识别。
### 字段(Field)
字段是 elasticsearch 的最小单位，一个文档里面有多个字段，每个字段就是一个数据字段。
### 来源字段(Source Field)
默认情况下，原文档将被存储在 _source 这个字段中，这个对象返回一个精确的 JSON 字符串，这个对象不显示索引分析后的其他任何数据。
### 主键(ID)
ID 是一个文件的唯一标识，如果在存库的时候没有提供 ID，系统会自动生成一个 ID，文档的 index/type/id 必须是唯一的。
## API 约定
Elasticsearch 的对外提供的 API 是以 HTTP 协议的方式通过 JSON 格式以 REST 约定的
### 通用参数
- pretty - 当在请求中添加参数 pretty=true 时，请求的返回值是经过格式化后的 JSON 数据，阅读起来更方便
- human - 当在请求中添加参数 human=true 时，返回结果的统计数据更适合人类阅读，默认是 false
- filter_path - 请求中添加参数 filter_path=content 时，可以过来返回值的内容，多个值支持 , 隔开和通配符；如 ``` curl -X GET HTTP '127.0.0.1:9200/_search?pretty=true&filter_path=took,hits._id,hits._score'```
### Head 插件安装
Head 插件是 Elasticsearch 的一个可视化界面
```shell
cd elasticsearch/bin
plugin install mobz/elasticsearch-head

http://127.0.0.1:9200/_plugin/head
```
## 索引
索引是具有相同结构的文档集合，对 Elasticsearch 的大部分操作都是基于索引来完成的。
### 索引管理
#### 创建索引
创建索引的时候可以通过 number_of_shards 和 number_of_replicas 参数指定索引的分片和副本数量；默认情况下分片的数量是5，副本的数量是1。
```json

PUT http://ip:port/index/

{
    "settings":{
        "index":{
            "number_of_shards":3,
            "number_of_replicas":2
        }
    }
}
```
#### 删除索引
删除索引需要指定索引名或者通配符，删除多个索引可以使用逗号(,)分隔或者使用 _all 或者 * 删除全部索引；为了防止误删除，可以设置 elasticsearch.yml 中 action.destructive_require_nam 属性为 true，禁止使用通配符或者 _all 删除索引，必须使用索引名称或者别名才能删除索引。
```json
DELETE http://ip:port/index/
```
#### 获取索引
获取索引会把系统中的信息都显示出来，包括一些默认的配置；获取索引需要指定索引名称，或者使用通配符获取多个索引，或者使用 _all 或 * 获取全部索引；如果索引不存在则返回一个错误内容。
```json
GET http://ip:port/index/
```
获取索引的时候可以指定返回特定的属性，多个属性使用逗号(,) 隔开，可指定的属性包括 _settings、_mappings、_warmers 和 _aliases。
```json
GET http://ip:port/index/_settings,_mappings,_warmers,_aliases
```
#### 打开/关闭索引
关闭的索引只能显示索引元数据信息，不能够进行读写操作；打开/关闭索引可以控制索引是否打开或关闭。
```json
POST http://ip:port/index/_open     打开索引
POST http://ip:port/index/_close    关闭索引
```
关闭的索引会继续占用磁盘空间而不能使用，所以关闭索引接口会造成磁盘空间的浪费，设置 settingscluster.indicices.close.enabled=false 即可禁止使用关闭索引功能，默认是 true。
### 索引映射管理
创建文档的时候如果没有指定索引参数，系统会自动判断每个维度的类型。
#### 增加映射
Elasticsearch 提供向索引添加文档类型或者向文档类型中添加字段。
```
PUT http://host:port/index/

{
	"mappings":{
		"<doc_type>":{
			"properties":{
				"<field_name>":{"type":"<field_type>"}
			}
		}
	}
}
```
#### 获取映射
可以通过索引获取文档映射，系统同时支持一次获取多个索引或文档的映射类型。
```
GET http://ip:port/index/_mapping/type1,type2

GET http://ip:port/_all/_mapping/type1,type2
```
#### 获取字段映射
可以只获取某个文档或索引的某个或多个字段而不是全部内容。可以使用逗号(,)分隔，也可以使用通配符。
```
GET http://{ip:port}/{index}/{type}/_mapping/field/{field}
```
#### 判断类型是否存在
```
HEAD http://{ip:port}/{index}/{type}
```
### 索引别名
Elasticsearch 可以对索引指定别名，通过别名可以查询到一个或多个索引的内容。在 Elasticsearch 内部别名会自动映射到索引上，可以针对别名设置过滤器或者路由，别名不能重复也不能和其他索引别名重复。
#### 增加索引别名
```
POST http://{ip:port}/_aliases

{
    "actions":[
	    {
		    "add":{
			    "index":"{index_name}",
				"alias":"{alias_name}"
			}
		}
	]
}
```
### 索引配置
### 索引监控
### 状态管理
### 文档管理
## 映射
## 搜索
## 聚合
## 集群
## 分词
## 配置
## 监控
## Java API
