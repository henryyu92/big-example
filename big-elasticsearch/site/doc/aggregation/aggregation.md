## 聚合

聚合提供了从数据中分组和提取数据的能力，Elasticsearch 可以在返回结果集的同时返回聚合结果。

```shell
GET /bank/_search
{
	"aggs":{
		"my-agg-name":{
			"terms":{
				"field":"my-field"
			}
		}
	}
}
```

