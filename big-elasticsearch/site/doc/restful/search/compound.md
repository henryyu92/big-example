## 组合查询

组合查询包装其他组合查询或者叶查询来组合查询结果和分数。组合查询可以分为几类：

- bool query
- // todo

### Boolean

`bool` 查询通过布尔组合多个查询条件查询匹配的文档，布尔查询由多个布尔子句构成。

- `must`：匹配的文档必须满足子句中的查询条件
- `must_not`：匹配的文档必须不满足子句中的查询条件
- `should`：匹配的文档可以满足也可以不满足子句中查询条件
- `filter`：`must` 和 `should` 条件满足会提升 score 但是 `filter` 条件不会影响 score

```shell
GET /bank/_search
{
	"query":{
		"bool":[
			"must":[
				{"match":{"age": 40}}
			],
			"must_not":[
				{"match":{"state": 10}}
			],
			"should":[
				{"match":{"lastname":"wallace"}}
			],
			"filter":[
				{"range":{"age":{"gte":10, "lte":20}}}
			]
		]
	}
}
```

`filter` 中指定的查询条件对查询的评分没有影响，评分只会受其他查询条件影响：

```shell
# 返回所有文档的分为都为 0
GET /my-index-001/_search
{
	"query":{
		"bool":{
			"filter":{
				"term":{
					"status": "active"
				}
			}
		}
	}
}
```

