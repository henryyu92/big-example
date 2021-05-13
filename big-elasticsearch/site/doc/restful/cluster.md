## 集群管理

Elasticsearch 提供了 API 用于集群的管理和监控。

#### 集群管理

Elasticsearch 集群 API 使用 `_cluster` 来

- `/_cluster/healty`：查看集群健康状态
- `/_cluster/state`：查看集群的元数据状态
- `/cluster/stats`：返回集群统计信息

```shell
# 查看集群健康状态
curl -X GET 'localhost:9200/_cluster/health?pretty'
```



#### 集群节点

通过 `/_nodes`  可以查看集群的节点信息，查看集群节点信息请求支持多种过滤方式，默认返回所有节点的信息：

```sh
# 查看集群所有节点
curl -X GET 'ip:port/_nodes'

# 查看 master 角色的节点信息
curl -X GET 'ip:port/_nodes/_master'

# 查看所有非 master 角色的节点信息
curl -X GET 'ip:port'/_nodes/_all,_master:false

# 查看指定节点的信息
curl -X GET 'ip:port'/_nodes/<node_id>
```



### 