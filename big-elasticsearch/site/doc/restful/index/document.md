## 文档

文档是 `Elasticsearch` 索引和搜索的最小数据单元，通常采用 JSON 的格式表示。每个文档都有唯一的 ID 标识，文档中的数据存储在 `_source` 的字段中。

`Elasticsearch` 提供了 RESTful API 用于创建、获取、删除文档。

### 索引文档

索引文档 API 向指定的索引添加 `JSON` 格式的文档，如果文档已经存在则更新文档并且增加文档的版本号。

```
PUT /<target>/_doc/<id>

POST /<target>/_doc/

PUT /<target>/_create/<id>

POST /<target>/_create/<id>
```

使用 `PUT` 方法需要指定文档 ID，而使用 `POST` 方法会自动生成 ID，如果索引不存在则会在创建文档的时候自动创建。

使用 `_create` 则明确表示创建文档，如果文档已经存在则会返回错误，使用 `_doc` 时如果文档不存在会自动创建文档，如果文档存在则会删除文档后重新创建文档(文档字段会发生变化)并且文档版本号加 1。

如果在创建索引的时候没有设置 `mapping` 则会采用动态 mapping 自动为文档的字段定义数据类型以及分词。

### 获取文档

`GET` API 从指定的索引中获取 JSON 格式的文档，返回的文档数据在字段 `_source` 中。

```sh
GET <index>/_doc/<id>

HEAD <index>/_doc/<id>

GET <index>/_source/<id>
```

使用 `GET` 方法可以获取到文档数据，而 `HEAD` 方法可以验证文档是否存在。使用 `_source` 则指定返回的文档只包含源数据。

### 删除文档

文档删除 API 使用 `DELETE` 方法删除指定索引的指定文档。文档删除时需要保证上次对文档的修改已经分配了版本号，否则会导致 `VersionConflictException`。

```sh
DELETE /<index>/_doc/<id>
```

索引的每个文档都有版本控制，对文档的每个写操作都会使得版本号递增，删除文档时可以指定文档的版本来确保需要删除的文档被删除并且没有更改。

删除文档不会导致文档数据立即删除，而是会保留一段时间用于并发控制，保留的时间由参数 `index.gc_deltes` 设置，默认是 60s。

### Multi get

使用 `mget` 可以从多个索引中获取多个文档，如果查询请求中指定了索引则只会获取指定索引的文档。

```sh
# 指定文档
curl -X GET /my-index-001/_mget -D
{
	"docs":{
		"type": "_doc",
		"_id": "1"
	}
}

# 不指定文档
GET /_mget
{
	"docs":[
		{
			"_index": "my-index-001",
			"_id": "1"
		},
		{
			"_index": "my-index-001",
			"_id": "2"
		}
	]
}
```



### Bulk

在单个请求中处理多个`index`、`create`、`delete`和 `update` 动作，动作在请求体中以独占一行的 JSON 的格式表示：

```sh
action_and_meta_data\n
optional_source\n
```

`index` 和 `create` 动作需要在下一行中指定源数据。

```shell
POST _bulk
{"index": {"_index": "test", "_id": "1"}}
{"field1": "value1"}
{"delete": {"_index": "test", "_id": "2"}}
{"create": {"_index": "test", "_id": "3"}}
{"field1": "value3"}
```



### Delete by query

删除匹配指定查询的文档

```
POST /<target>/_delete_by_query
```

请求提交后 `Elasticsearch` 在开始处理请求并且删除匹配的文档前会生成索引数据的快照，在生成快照后到删除文档前的时间段内，使文档产生变化的请求(delete, update)会由于版本冲突导致删除失败。

```sh
POST /my-index-001/_delete_by_query
{
	"query":{
		"match": {
			"user.id": "elkbee"
		}
	}
}
```

`Elasticsearch` 会按照顺序执行多个搜索请求来查找到匹配的文档，然后对每批文档执行批量删除。如果请求或者批量删除失败，则请求会重试最多 10 次，如果重试之后仍不能成功则停止处理，并在响应中返回失败的请求。任何成功完成删除的请求仍然保持不变而不会被回滚。