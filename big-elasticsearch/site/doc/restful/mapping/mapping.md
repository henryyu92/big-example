## Mapping

`Mapping` 是定义文档及其包含的字段如何存储和索引的过程。

文档是字段的集合，每个字段都有自己的数据类型，`Mapping` 根据定义的文档字段映射关系将数据和文档关联，映射关系定义中还包括元数据字段用于定义文档关联的元数据如何处理。

`Elasticsearch` 提供了动态映射和显式映射来定义文档字段映射关系，动态映射会自动推断数据的类型，而显式映射则需要在创建索引时显式的指定字段的类型等元数据。



### 动态映射

`ElasticSearch` 可以在创建文档的时候自动添加字段，并自动的为字段推断类型。

```shell
PUT data/_doc/1
{
	"count": 5
}
```



### 显式映射

显式映射可以精确的控制映射关系，显式映射可以在创建索引的时候设置：

```shell
PUT /my-index-001
{
	"mapping":{
		"properties":{
			"age": {"type": "integer"},
			"email": {"type": "keyword"},
			"name": {"type": "text"}
		}
	}
}
```

对于已经存在的索引，可以为其添加新的字段映射关系：

```shell
PUT /my-index-001/_mapping
{
	"properties":{
		"employee-id":{
			"type": "keyword",
			"index": false
		}
	}
}
```

除了一些支持更改的映射关系，`Elasticsearch` 不允许修改已经存在的字段的数据类型。

### 查看 Mapping

`Elasticsearch` 提供了查询 Mapping 的 `API` ：

```shell
GET /my-index-001/_mapping
```

查看全部的 Mapping 数据会比较多，还可以查询指定字段的 Mapping 信息：

```shell
GET /my-index-001/_mapping/field/employee-id
```

可以只获取某个文档或索引的某个或多个字段而不是全部内容。可以使用逗号(,)分隔，也可以使用通配符。