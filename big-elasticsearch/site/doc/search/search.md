## 搜索

搜索查询是 Elasticsearch 提供的对索引文档信息的请求，搜索查询由单个或者多个子查询组成，并返回满足搜索条件的文档信息。

搜索查询可以包含附加信息用于更高的查询，例如可以设置返回的数据量。

Elasticsearch 提供了搜索 API 检索存储的文档数据，搜索 API 接受以 DSL（`Domain Specific Language`） 格式的请求体：

```shell
# 匹配包含 user.id 字段并且值为 kimchy 的文档

GET /my-index-001/_search
{
	"query":{
		"match":{
			"user.id": "kimchy"
		}
	}
}
```

### 分页

#### `From` 和 `Size`

默认情况下，搜索返回前面 10 条匹配的文档数据，如果需要返回更多的数据则需要使用 `from` 和 `size` 参数来分页返回数据集，`from` 表示需要跳过的命中数据量，`size` 则表示返回命中数据的最大数量。

```shell
# 返回匹配文档的 5~25

GET /my-index-001/_search
{
	"query":{
		"match":{
			"user.id":"kimchy"
		}
	},
	"from": 5,
	"size": 20
}
```

`from` 和 `size` 参数需要先查询出所有在 from 前面匹配到的文档，然后过滤掉前面匹配的数据。搜索请求通常会跨越多个分片，每个分片都必须将命中的数据存储在内存中，如果 `from` 过大或者 `size` 过大则会占用大量内存，使得节点性能下降。

默认情况下，使用 `from` 和 `size` 不能使得页大小超过 10000 个命中数据，这个限制由参数 `index.max_result_window` 设置，如果需要页大小需要超过 10000 个命中数据，则可以使用 `search_after` 参数。

#### Search after

// todo

### 排序

默认情况下，查询返回的数据根据表示文档与请求参数匹配程度的 `_score` 字段排序，Elasticsearch 提供了 `sort` 请求参数来指定排序的字段以及排序规则。

```shell
# 
GET /my-index-001/_search
{
	"query":{
		"match":{
			"user.id":"kimchy"
		}
	},
	"sort":[
		{"name": "desc"},
		{"age": "asc"}
	]
}
```



### 指定字段

默认情况下，查询返回匹配到的文档的所有字段，Elasticsearch 提供了两种方式来约束返回获取指定的字段：

- `fields` 参数指定在 mapping 中的的字段
- `_source` 参数指定数据的原始字段

通常更加倾向于使用 `fields` 参数，因为这种方式兼顾了文档数据和索引 mapping。

#### `fields` 

```shell
GET /my-index-001/_search
{
	"query":{
		"match":{
			"user.id": "kimchy"
		}
	},
	"fields":[
		"user.id",
		"http.response.*"
	]
}
```



#### `_source`

### 脚本字段