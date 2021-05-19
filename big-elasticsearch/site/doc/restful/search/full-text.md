## 全文查询

全文查询可以查询被分词的文本字段，索引时查询参数会使用和字段存储的数据一样的规则进行分词处理。

查询时会先对输入的查询进行分词，然后每个词项逐个进行底层的查询，最终将结果进行合并，并为每个文档生成一个算分。

### Match

`match` 查询返回与查询条件提供的文本、数字、日期或者布尔值匹配的文档，查询条件的文本会被分词。

```shell
GET /my-index-001/_search
{
	"query":{
		"match":{
			"user.name": "tom"
		}
	}
}
```

`match` 查询的类型为 `boolean`，也就是说在分词的过程中构造了一个布尔查询，根据 `operator` 设置的值来确定最后匹配的文档。

`match` 查询包含许多有用的查询选项：

- `operator`：可以是 `and` 或者 `or` 表示在查询条件分词后的逻辑关系，`and` 表示分词后需要都匹配，而的`or` 表示只要有一个匹配即可，默认是 `or`
- `fuzziness`：设置匹配过程中允许的最大编辑距离，可以用于模糊匹配

### Match phrase

`match_phrase` 查询将查询条件当作一个整体而不会进行分词

```shell
GET /bank/_search
{
	"query":{
		"match_phrase":{
			"address":"mill lane"
		}
	}
}
```



### Multi match

`multi_match` 查询指定字段中任意一个匹配查询的参数，查询参数会进行分词

```shell
GET /bank/_search
{
	"query":{
		"multi_match":{
			# state 或者 address 任意一个包含 mill
			"query": "mill",
			"fields":["state", "address"]
		}
	}
}
```

### Query string

使用语法解析器解析查询字符串并返回匹配的文档。语法解析器基于 `AND` 和 `NOT` 这种操作符将查询字符串分割为多个查询参数，分割的查询参数独立进行查询，然后将查询的文档进行组合。

`query_string` 查询可以用于复杂的查询，如果查询字符串中有语法错误的话会返回错误。

```shell
GET /my-index-001/_search
{
	"query":{
		"query_string":{
			"query": "(new york city) OR (big apple)",
			"default_field": "content"
		}
	}
}
```

 

### Simple query string

