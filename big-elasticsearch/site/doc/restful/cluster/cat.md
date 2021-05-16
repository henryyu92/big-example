cat API 接受一个查询字符串参数，用于帮助查看它们提供的所有信息。`/_cat` 命令可以查看所有可用的命令。

```sh
curl -X GET 'ip:port/_cat?pretty'

# 查看 Master 节点信息
curl -X GET 'ip:/port/_cat/master?v&pretty'
```

- `/_cat/health`：返回集群的健康状态

- `/_cat/nodes`：返回集群节点的相关信息，返回的信息包括

- `/_cat/master`：返回主节点的信息 

- `/_cat/indices`：返回集群中索引的信息

- `/_cat/alias` 别名信息

- `/_cat/shards`：返回集群中索引的分片信息