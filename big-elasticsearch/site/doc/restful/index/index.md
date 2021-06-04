## 索引

索引是具有相同结构的文档集合，`Elasticsearch` 提供了 RESTful API 用于索引的管理。

### 创建索引

创建索引 API 用于向 `Elasticsearch` 集群添加新的索引，创建索引时可以指定 `mapping`、`setting` 和 `aliase` 属性。

```sh
curl -X PUT /<index-name>
```



```shell
# 创建索引
PUT /my-index-001
{
	"settings":{
		"number_of_shards": 3,
		"number_of_replicas": 2
	},
	"mappings":{
		"properties":{
			"field1":{"type": "text"}
		}
	},
	"aliases":{
		"alias_1":{},
		"alias_2": {
			"filter": {
				"term": {"user.id": "kimchy"}
			},
			"routing": "shard-1"
		}
	}
}
```

### 获取索引

获取索引 API 用于获取索引的信息，包括 `settings`， `mappings`、`aliases` 等信息。

```shell
GET /my-index-001

HEAD /my-index-001
```

使用 `Head` 请求可以查看指定索引是否存在，返回的响应码为 200 表示索引存在，响应码为 404 则表示不存在。

### 删除索引

删除索引 API 用于删除存在的索引，删除索引 API 使用 `Delete` 请求。

```shell
DELETE /my-index-001
```

### 关闭索引

关闭索引 API 使用 `POST` 请求关闭开启状态的索引。索引关闭后读写请求会阻塞，并且处于开启状态时的操作都不允许，这使得集群不需要为关闭的索引维护用于搜索的数据结构从而减少集群的开销。

```shell
POST /my-index-001/_close
```

### 开启索引

### 收缩索引

### 分裂索引

索引分裂 API 将索引分裂成多个新的索引，索引的每个主分片也会分裂成对应数量的主分片。

```sh
POST /my-index-001/_split/split_my-index-001
{
	"settings":{
		"index.number_of_shards": 2
	}
}
```

能够被分裂的索引需要具备两个条件：

- 索引是只读的
- 集群健康状态是绿色的