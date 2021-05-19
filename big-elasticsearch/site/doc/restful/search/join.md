## 连接查询

分布式系统中执行完整的 SQL 连接查询的代价高昂，`Elasticsearch` 提供了两种基于水平扩展的连接查询。

### Nested

嵌套查询用于搜索嵌套的字段对象，字段中的每个对象独立的作为文档进行查询，如果对象与搜索条件匹配则返回根文档。

```shell
GET /my-index-001/_search
{
	"query":{
		"nested": {
			# 嵌套对象
			"path": "obj1",
			"query":{
				"bool": {
					"must":{
						"match": {"obj1.name": "blue"},
						"match": {"obj1.count": {"gt": 5}}
					}
				}
			}
		}
	}
}
```

- `path`：
- `score_mode`

多级嵌套

```shell
GET /drivers/_search
{
	"query":{
		"nested":{
			"path": "driver",
			"query": {
				"nestd": {
					"path": "driver.vehicle",
					"query": {
						"bool": {
							"must": [
								{"match": {"driver.vehicle.make": "Powell Motors"}},
								{"match": {"driver.vehicle.model": "Canyonero"}}
							]
						}
					}
				}
			}
		}
	}
}
```



### Has child

### Has parent

