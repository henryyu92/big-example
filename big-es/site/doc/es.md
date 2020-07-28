## Elasticsearch

Elasticsearch 是一个高扩展性的分布式全文检索引擎，可以近乎实时的存储、检索数据，可以扩展到上百台服务器，处理 PB 级的数据。

### 核心概念

索引：
文档：
分片：

物理设计： elasticsearch 在后台把每个索引划分成多个分片，每个分片可以在集群中的不同服务器间迁移。elasticsearch 是以集群方式部署的，即使只有一个节点，集群的默认名称为 elasticsearch。

逻辑设计：一个索引中可以包含多个文档，elasticsearch 是面向文档的，索引和搜索的最小单位是文档，文档是 json 格式的。索引是文档的集合，索引被分片到多个节点上存储。


### Rest

```
put  localhost:9200/索引/_doc/id        创建指定 id 文档
post localhost:9200/索引/_doc           创建随机 id 文档
post localhost:9200/索引/_update/id     修改文档
delete localhost:9200/索引/id           删除指定 id 文档
get  localhost:9200/索引/id             查询指定 id 文档
post localhost:9200/索引/_search        查询所有数据
```


### 索引操作



### 文档查询