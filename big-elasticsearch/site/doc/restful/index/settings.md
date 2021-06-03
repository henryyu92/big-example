## Setting

`Setting` 定义了创建索引时的一些设置，包括索引分片、分片副本等。索引 `settting` 分为静态部分和动态部分，其中静态部分只能在创建索引的时候定义，并且不能修改，而动态部分可以动态的通过 `Elasticsearch` 提供的 API 修改。

静态设置包括：

- `index.number_of_shard`：索引需要的主分片数，默认是 1

动态设置包括：

- `index.number_of_replicas`：每个主分片的副本数，默认是 1

### 获取索引 `setting`

通过 `_settings` 请求可以获取索引的 `setting` 信息，可以通过 `,` 分隔在一次查询中获取多个索引的多个设置信息。

```sh
# 获取指定索引的所有 setting 信息
GET /<target>/_settings

# 获取指定索引的指定 setting 信息
GET /<target>/_settings/<settings>
```

### 更新索引 `setting`

