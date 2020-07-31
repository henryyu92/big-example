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

设置索引

```sh
curl -X POST 'http://localhost:9200' -d '{

}'
```





### 文档操作