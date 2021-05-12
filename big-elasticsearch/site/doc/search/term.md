## Term 查询

`term` 级别的查询用于查询文档中的精确字段。和全文检索不同的是，term 级别的查询不会对查询条件进行分词，而是进行精确匹配字段。

### Exists

返回查询字段存在可以被索引的值的文档，字段在文档中不存可以被索引的值有多种原因：

- 字段在文档中的值是 null 或者 []
- mapping 中设置该字段了 `"index": false`
- 字段的长度超过了 mapping 中 `ignore_above` 设置的值
- mapping 中设置了 `ignore_malformed` 并且字段的值格式不正确

```shell
GET /my-index-001/_search
{
	"query":{
		"exists":{
			"field": "user"
		}
	}
}
```



### Range

返回包含指定查询字段并且字段值在给定的范围内的文档

```shell
GET /my-index-001/_search
{
	"query":{
		"range":{
			"age":{
				"gte": 10,
				"lte": 20
			}
		}
	}
}
```



### Term

返回**精确匹配** 给定查询参数的文档，也就是说查询参数不会被分词，通常用于不需要进行分词的字段。当使用 `term` 查询文本字段时，由于文本字段会被分词，因此很难刚好匹配(除非文本不能进行分词)。

```shell
# 精确匹配 user.id 为 kimchy 的文档
GET /my-index-001/_search
{
	"query":{
		"term":{
			"user.id": {
				"value": "kimchy"
			}
		}
	}
}
```

### Terms

返回**精确匹配**任意给定查询参数的文档，和 `term` 查询的区别就是可以设置多个匹配字段，只要有一个匹配即可。

```shell
GET /my-index-001/_search
{
	"query":{
		"terms":{
			"user.id": ["kimchy", "elkbee"]
		}
	}
}
```

