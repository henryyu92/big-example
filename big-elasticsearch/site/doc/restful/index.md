## 索引

### 索引管理

#### 创建索引

创建索引 API 用于向 `Elasticsearch` 集群添加新的索引，创建索引时可以指定可选的设置：

- `settings`：索引的配置
- `mappings`：索引的 Mapping 定义
- `aliases`：索引中的别名

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

#### 索引存在

使用 `Head` 请求可以查看指定索引是否存在，返回的响应码为 200 表示索引存在，响应码为 404 则表示不存在。

```shell
HEAD /my-index-001
```

#### 获取索引

获取索引 API 用于获取索引的信息，包括 `settings`， `mappings`、`aliases` 等信息。

```shell
GET /my-index-001
```

#### 删除索引

删除索引 API 用于删除存在的索引，删除索引 API 使用 `Delete` 请求。

```shell
DELETE /my-index-001
```

#### 关闭索引



### Mapping 管理

#### 更新 Mapping

#### 获取 Mapping

### Alias 管理

#### 创建别名

#### 删除别名

#### 别名存在

### Setting 管理

#### 更新 Setting

#### 获取 Setting