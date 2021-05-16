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



- `Bucket Aggregation` 满足特定条件的文档的集合
- `Metric Aggregation` 数学运算，可以对文档字段进行统计分析
- `Pipeline Aggregation` 对其他的集合结果进行二次聚合
- `Matrix Aggregation` 支持多个字段的操作并提供一个结果矩阵