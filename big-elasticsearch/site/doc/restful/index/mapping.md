## Mapping

`Mapping` 是定义文档及其包含的字段如何存储和索引的过程。文档是字段的集合，每个字段都有自己的数据类型，`Mapping` 根据定义的文档字段映射关系将数据和文档关联，映射关系定义中还包括元数据字段用于定义文档关联的元数据如何处理。

`Elasticsearch` 提供了动态映射和显式映射来定义文档字段映射关系，动态映射会自动推断数据的类型，而显式映射则需要在创建索引时显式的指定字段的类型等元数据。

- **动态映射**：在创建文档的时候自动为字段推断数据类型
- **显式映射**：需要在创建索引的时候设置字段的类型，设置之后不允许修改，后续创建该索引的文档时字段的类型即为设置的类型

### 查看 Mapping

使用 `_mapping` 方法可以查看 `Mapping` 的定义，如果 `Mapping` 中定义的数据较多，可以在查询中指定字段。

```sh
# 查询所有的 mapping 定义
GET /_mapping

# 查询指定索引的 mapping 定义
GET /<target>/_mapping

# 查询索引的 mapping 的指定字段
GET /<target>/_mapping/field/<field-name>
```

查看 `Mapping` API 支持使用 `,` 分割的方式查看多个索引的多个字段的映射

```sh
# 获取多个索引的 mapping 定义
GET /my-index-001,my-index-002/_mapping

# 获取索引的多个字段映射
GET /my-index-001/_mapping/field/field1,field2
```

### 更新 Mapping

可以像已经存在的 `mapping` 添加新的字段映射，但是已经存在的字段类型映射不允许修改。

```sh
PUT /my-index-001/_mapping
{
	"properties":{
		"user":{
			"properties":{
				"name":{
					"type": "keyword"
				}
			}
		}
	}
}
```

如果需要修改 `mapping` 中已经存在的字段类型，可以通过先创建新的有正确 `mapping` 的索引，然后通过 `reindex` 将当前索引的数据移动到新的索引。

### Mapping 设置

- `index` 设置指定的字段是否可以被索引
- `copy_to`



