## RESTful API

ES 提供了 RESTful API 用于操作索引和文档。

### 索引操作

```properties
# 查看所有索引
GET /_cat/indices

# 查看指定索引的设置
GET /indices_name

# 创建索引
PUT /indice_nam
```

设置索引 settings，`number_of_shards`  是 ES 分片数，需要在创建索引时指定，一旦指定就不能修改

```sh
curl -H "Content-Type: application/json" -X PUT 'http://localhost:9200/hello/_settings?pretty' -d '{
  "settings": {
    "number_of_replicas": 1
  }
}'
```

查看索引 settings

```sh
curl -X GET 'http://localhost:9200/hello/_settings?pretty'
```



设置索引 mapping

```sh
curl -H "ContentType: application/json" -X PUT 'http://localhost:9200/hello/_mapping' -d '{
	"properties":{
		"field_name":{
			"type":"text"
		}
	}
}'
```

查看索引 mapping

```sh
curl -X GET 'http://localhost:9200/hello/_mapping?pretty'
```





### 文档操作