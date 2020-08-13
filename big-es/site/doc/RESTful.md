## RESTful API

ES 提供了 RESTful API 用于操作索引和文档。

> - GET
> - PUT
> - POST
> - DELETE



> tips:
>
> - 在请求中加入  `pretty`  参数可以使得返回的结果是 Json 格式的，便于阅读

### 集群

#### 集群节点

通过 `/_nodes`  可以查看集群的节点信息，查看集群节点信息请求支持多种过滤方式，默认返回所有节点的信息：

```sh
# 查看集群所有节点
curl -X GET 'ip:port/_nodes'

# 查看 master 角色的节点信息
curl -X GET 'ip:port/_nodes/_master'
```



查看集群状态

```sh
curl -X GET 'localhost:9200/_cluster/health?pretty'
```



### 索引

索引是具有相同结构的文档集合，对 Elasticsearch 的大部分操作都是基于索引来完成的。

#### 创建索引

创建索引的时候可以通过  `number_of_shards`  和  ` number_of_replicas`  参数指定索引的分片和副本数量，默认情况下分片的数量是5，副本的数量是1。

```sh
curl -H 'Content-Type:application/json' -X PUT 'ip:port/indices_name?pretty' -d '{
	"settings":{
		"number_of_replicas": 1,
		"number_of_shards": 1
	}
}' 
```

**索引的分片数在创建时指定后就不允许修改**，因此在创建索引时需要规划好分片数量。索引分片的副本数量可以在使用的过程中动态的调整。

```sh
curl -H 'Content-Type:application/json' -X PUT 'ip:port/indices_name/_setting?pretty' -d '{
	"settings":{
		"number_of_replicas": 3
	}
}'
```

#### 删除索引

删除索引需要指定索引名，删除多个索引可以使用逗号  `,`  分隔，删除全部索引使用 `_all` 或者使用通配符 `*`  。

为了防止误删除，可以设置 `elasticsearch.yml` 中设置属性 `action.destructive_require_nam: true`  禁止使用通配符或者 _all 删除索引。

```sh
# 删除全部索引
curl -X DELETE 'ip:port/_all?pretty'

# 删除指定索引
curl -X DELETE 'ip:port/indices_name?pretty'
```

#### 查看索引

查看索引需要指定索引名称，查看全部索引可以使用 `_all`， 如果索引不存在则返回一个错误内容。

```sh
# 查看指定索引
curl -X GET 'ip:port/indices_name?pretty'

# 查看全部索引
curl -X GET 'ip:port/_all?pretty'
```

获取到的索引包含索引的  `mapping`, `setting`  和 `alias` 信息，在查看索引是可以单独指定获取到的信息。

```sh
# 获取索引的 settings 信息
curl -X GET 'ip:port/indices_name/_settings?pretty'

# 获取索引的 mappings 信息
curl -X GET 'ip/port/indices_name/_mapping?pretty'

# 获取索引的 aliases 信息
curl -X GET 'ip/port/indices_name/_alias?pretty'
```

查看索引中的文档

```sh
# 查看索引的文档总数
curl -X GET 'ip:port/indices_name/_count?v'
```



设置索引 mapping

```sh
curl -H "ContentType: application/json" -X PUT 'http://localhost:9200/hello/_mapping' -d '{
	"properties":{
		"field_name":{
			"type":"text"
		}
	}
}'
```

查看索引 mapping

```sh
curl -X GET 'http://localhost:9200/hello/_mapping?pretty'
```

#### 索引别名

Elasticsearch 可以对索引指定别名，通过别名可以查询到索引的内容。在 Elasticsearch 内部别名会自动映射到索引上，可以针对别名设置过滤器或者路由，别名不能重复也不能和其他索引别名重复。别名可以在创建索引的时候指定

```sh
# 创建索引时指定索引别名
curl -H 'Content-Type:application/json' -X PUT 'ip:port/indices_name?pretty' -d '{
	"alias":{
		"alias_name1":{},
		"alias_name2":{}
	}
}'
```

创建索引后通过 `_aliases` 可以对别名进行添加或者删除操作：

```sh
curl -H 'Content-Type:application/json' -X POST 'ip:port/_aliases' -d '{
	"actions":[
		{ "add":{ "index": "indices_name", "alias": "alias_name"}},
		{ "remove":{ "index": "indices_name", "alias": "alias_name"}}
	]
}'
```

// todo

索引别名可以用于设置过滤器或者路由

#### Mapping

#### Setting

#### 索引监控

### 文档

文档具有版本信息，每次对文档的修改都会使得版本号增加 1

#### 索引文档

索引文档有 `Put`  和  `POST` 两种方式，使用 `PUT` 时如果文档已经存在则会先删除文档然后创建新的文档，而 `POST` 方式在文档存在时会失败。 

```sh
# 使用 PUT 方法
curl -H 'Content-Type:application/json' -X PUT 'ip:port/index_name/doc_name/doc_id' -d '{
	"field":"value"
}'

# 使用 POST 方法，不指定文档 ID 则自动生成 ID
curl -H "Content-Type:application/json" -X POST 'ip:port/index_name/doc_name' -d '{
	"field": "value"
}'

# 使用 POST 方法，指定文档 ID
curl -H 'Content-Type:application/json' -X POST 'ip:port/index_name/doc_name/doc_id' 
-d '{
	"field": "value"
}'
```

#### 获取文档

获取文档使用 `GET` 方法，获取单个文档需要指定文档 ID

```sh
curl -X GET 'ip:port/index_name/doc_id'
```

#### 修改文档

修改文档使用 `POST` 方法，被修改的文档必须已经存在，且只修改对应的字段

```sh
curl -H 'Content-Type:application/json' -X POST 'ip:port/index_name/doc_name/doc_id' 
-d '{
	"field": "value"
}'
```

#### 删除文档

删除文档使用 `DELETE` 方法，删除文档时需要指定文档的 ID

```sh
curl -X DELETE 'ip:port/index_name/doc_name/doc_id'
```

### _cat
cat API 接受一个查询字符串参数，用于帮助查看它们提供的所有信息。`/_cat` 命令可以查看所有可用的命令。

```sh
curl -X GET 'ip:port/_cat?pretty'

# 查看 Master 节点信息
curl -X GET 'ip:/port/_cat/master?v&pretty'
```

#### 集群状态

`/_cat/health`：返回集群的健康状态

#### 节点信息

`/_cat/nodes`：返回集群节点的相关信息，返回的信息包括



`/_cat/master`：返回主节点的信息 

#### 索引信息

`/_cat/indices`：返回集群中索引的信息



`/_cat/alias` 别名信息

#### 分片信息

`/_cat/shards`：返回集群中索引的分片信息

### Bulk API

### Search API
