## TableEnvironment

使用 Table API 和 SQL 之前需要引入依赖
```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-table</artifactId>
</dependency>
```
Table API 和 SQL 需要在环境中创建 TableEnvironment 对象，TableEnvironment 中提供了注册内部表、执行 Flink SQL 语句、注册自定义函数等功能。
```scala
// 创建流式应用对应地 TableEnvironment
val streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
val tableStreamEnv = TableEnvironment.getTableEnvironment(streamEnv)

// 创建批处理应用对应的 TableEnvironment
val batchEnv = ExecutionEnvironment.getExecutionEnvironment()
val tableBatchEnv = TableEnvironment.getTableEnvironment(batchEnv)
```

### 内部 Catalog 注册

获取 TableEnvironment 对象后就可以注册数据源和数据表信息。所有数据库和表的元数据信息存放在 CataLog 内部目录结构中。

通过 TableEnvironment 可以注册数据表，Flink 提供了内部 Table 的注册，Table 可以通过 StreamTableEnvironment 提供的接口生成，也可以从 DataStream 或者 DataSet 转换而来
```scala
val tableEnv = TableEnvironment.getTableEnvironment(env)
// 生成 Table
val projTable = tableEnv.scan("SensorsTable").select(...)
// 注册内部表
tableEnv.registerTable("projectedTable", projTable)
```
数据表注册后，在内部 CataLog 中就会生成相应的数据表信息，注册在 CataLog 中的 Table 类似与关系型数据库中的视图结构，当注册的表被引用和查询时数据才会在对应的 Table 中生成。

Flink 支持直接将外部数据源注册成 Table 数据结构，Flink 内部实现了大部分常用的数据源
```scala
val tableEnv = TableEnvironment.getTableEnvironment(env)
tableEnv.registerTableSource("CsvTable", new CsvTableSource("/path/to/file", ...))
```
Table API 提供 TableSink 用于将处理完成的数据写入到外部系统，TableSink 在 TableEnvironment 中注册需要输出的表，SQL 查询处理之后产生的结果将插入 TableSink 对应的表中，最终达到数据输出到外部系统的目的.
```scala
val tableEnv = TableEnvironment.getTableEnvironment(env)
val fieldNames = Array("f1", "f2", "f3")
val fieldTypes: Array[TypeInformation[_]] = Array(Types.INT, Types.DOUBLE, Types.LONG)
tableEnv.registerTableSink("CsvSinkTable", fieldNames, fieldTypes, new CsvTableSink("path/csv", ","))
```

### 外部 CataLog

Flink 支持使用外部 CataLog 作为表的元数据存储，外部 CataLog 需要实现 ExternalCataLog 接口，并且在 TableEnvironment 中完成注册。
```scala
val tableEnv = TableEnvironment.getTableEnvironment(env)
tableEnv.registerExternalCataLog("InMemCataLog", new ExternalCataLog(){

})
```

### DataStream 或 DataSet 与 Table 相互转换

通过 Table API 可以将 Table 和 DataStream 以及 DataSet 之间相互转换。

Flink 提供两种方式将 DataStream 或者 DataSet 转换为 Table，一种是通过注册 Table 的方式将 DataStream 或者 DataSet 注册成 CataLog 中的表，这种方式需要指定表名和包含字段名称，另外一种是将 DataStream 或者 DataSet 转换成 Table 结构，然后使用 Table API  操作创建后的 Table。
```scala
val streamEnv = StreamExecutionEnvironemnt.getExecutionEnvironment
val tableStreamEnv = TableEnvironment.getTableEnvironment(streamEnv)
val stream = streamEnv.fromElements((192, "foo"), (122, "fun"))
// 将 DataStream 注册成 Table
tableStreamEnv.registerDataStream("table1", stream, "field1", "field2")
// 将 DataStream 转换成 Table
val table1 = tableStreamEnv.fromDataStream(stream,"field1", "field2")


val batchEnv = ExecutionEnvironment.getExecutionEnvironment
val tableBatchEnv = TableEnvironment.getTableEnvironment(batchEnv)
val dataSet = batchEnv.fromElements((192, "foo"), (122, "fun"))
// 将 DataSet 注册成 Table
tableBatchEnv.registerDataSet("table", dataSet, "field1", "field2")
// 将 DataSet 转换成 Table
val table2 = tableBatchEnv.fromDataSet(dataSet, "field1", "field2")

```
Flink 应用程序中也可以将 Table 转换为 DataStream 或者 DataSet，在转换的过程中需要指明目标数据集的字段类型

Table 转换为 DataStrem 时需要设置数据输出的模式，Flink 支持 Append Mode 和 Retract Mode 两种模式，Append 模式采用追加的方式将数据追加到 DataStream 中，Retract 模式根据一个 boolean 类型的字段判断是插入还是删除数据
```scala
val streamEnv = StreamExecutionEnvironemnt.getExecutionEnvironment
val tableStreamEnv = TableEnvironment.getTableEnvironment(streamEnv)
val stream = streamEnv.fromElements((192, "foo"), (122, "fun"))
val table = tableStreamEnv.fromDataSteam(stream)
// Append 模式转换
val appendSteam: DataStream[(Long, String)] = tableStreamEnv.toAppendStream[(Long, String)](table)
// Retract 模式
val retractStream: DataStrem[Boolean, Row] = tableStreamEnv.toRetractStream[Row](table)
```
Table 转换为 DataSet 时只需要直接转换即可
```scala
val batchEnv = ExecutionEnvironment.getExecutionEnvironment
val tableBatchEnv = TableEnvironment.getTableEnvironment(batchEnv)
val dataSet = batchEnv.fromElements((192, "foo"), (122, "fun"))
val table = tableBatchEnv.fromDataSet(dataSet)

// 将 Table 转换成 DataSet
val rowDa: DataSet[Row] = tableBatchEnv.toDataSet[Row](table)
```
### Schema 字段映射

Table 和 DataStream 或者 DataSet 的字段并不是完全匹配的，通常情况下需要在创建 Table 的时候需改字段的映射关系，Flink Table Schema 可以通过基于字段便宜位置和字段名称两种方式与 DataStream 或者 DataSet 中的字段进行映射。

#### 字段位置映射
字段位置映射是根据数据集中字段位置偏移来确认 Table 中的字段，当使用字段位置映射是需要注意数据集中的字段名称不能包含在 Schema 中，否则 Flink 会认为映射的字段是在原有的字段之中，将会直接使用原来的字段作为 Table 中的字段属性。

```scala
val tableEnv = TableEnvironment.getTableEnvironment(env)
val stream: DataStream[T] = ...
// 将 DataStream 转换成 Table，没有指定字段名称则使用默认值 _1, _2
val table = tableEnv.fromDataStream(stream)
// 使用指定的字段名称
val table2 = tableEnv.fromDataSteam(stream, "f1", "f2") 
```

#### 字段名称映射
字段名称映射是指在 DataStream 或 DataSet 中使用数据中的字段名称进行映射，字段名称映射相对于偏移位置更加灵活。可以使用字段名称构建 Table 的 Schema 或者对字段重命名，也可以对字段进行重排序和投影输出。
```scala
val tableEnv = TableEnvironment.getTableEnvironment(env)
val stream: DataStream[T] = ...

// 使用默认的字段名 _1, _2
val table = tableEnv.fromDataStream(stream)
// 仅获取字段名称 _2 的字段
val table2 = tableEnv.fromDataStream(stream, '_2)
// 交换字段的位置
val table3 = tableEnv.fromDataStream(stream, '_2, '_1)
// 交换字段位置并且重命名
val table3 = tableEnv.fromDataStream(stream, '_2 as 'field1, '_1 as 'field2)
```

## 外部连接器

在 Table API 和 SQL 中，Flink 可以通过 Table connector 直接连接外部系统，将批量或者流式数据从外部系统中获取到 Flink 系统中，或者从 Flink 系统中将数据发送到外部系统中.

Table API 提供了 TableSource 从外部系统获取数据，TableSink 将数据输出到外部系统中。为了能够在编写应用程序是通过配置化的方式直接使用已经定义好的数据源，Flink 提供了 Table Connector，将 TableSource 和 TableSink 的定义和使用分离。

```scala
tableEnv
  .connect(...)   // 指定 Table Connector Descriptor
  .withFormat(...)    // 指定数据格式
  .withSchema(...)    // 指定表结构
  .inAppendMode()     // 指定更新模式
  .registerTableSource("tableName")   // 注册 TableSource  
```

#### Table Connector
Flink 使用 org.apache.flink.table.descriptor.Descriptor 接口实现类来创建 Table Connector 实例，Flink 内置了多种 Table Connector

FileSystemConnector 允许从本地或者分布式文件系统中读取和写入数据
```scala
tableEnvironment.connect(new FileSystem().path("file:///path/filename"))
```
KafkaConnector 支持从 Kafka 的 Topic 中消费和写入数据
```scala
tableEnvironment.connect(new Kafka().version("0.11").topic("topic").sinkPartitionerFixed())
```
#### TableFormat

Flink 提供了常用的 TableFormat 可以在 Table Connector 中使用，以支持不同格式类型的数据传输。

CSVFormat 指定分隔符切分数据记录中的字段
   ```scala
tableEnvironment.withFormat(new Csv().field("field1", Types.STRING).fieldDelimiter(",").lineDelimiter("\n"))
   ```
JSONFormat 支持将读取或写入的数据映射成 JSON 格式，Flink 提供了三种方式定义 JSONForamat。
```scala
.withFormat(
  new Json().failOnMissingField(true)
    .schema(Type.ROW(...))    // 使用 Flink 数据类型定义，然后通过 Mapping 映射成 JSON Schema
    .jsonSchema("{type:'object', properties:{id: {type: 'number'}, ...}}")    // 通过配置 jsonSchema 构建 JSON FORMAT
    .deriveSchema()           // 直接使用 Table 中的 schema 信息，转换成 JSON 结构
)
```
AvroFormat 可以支持读取和写入 Avro 格式数据，AvroFormat 的结构可以通过定义 Avro 的 SpecificRecordClass 来实现或者通过指定 avroSchema 的方式来定义。
```scala
.withFormat(
  new Avro()
    .recordClass(MyRecord.class)    // 通过 AvroSpecificRecordClass 定义
    .avroSchema("{\"type\": \"record\", \"name\":\"event\", \"fields\":[{\"name\": \"id\", \"type\":\"long\"}]}")
)
```
### TableSchema

TableSchema 定义了 Flink Table 的数据表结构，包括字段名称、字段类型等信息，同时 TableSchema 会和 TableFormat 相匹配，在 Table 数据输入或者输出的过程中完成 Schema 的转换。但是当 Table Input/Output Format 和 Table Schema 不一致的时候需要相应的 Mapping 关系来完成映射。
```scala
.withSchema(
    new Schema()
      .field("id", Types.INT)   // 指定第一个字段的名称和类型
      .field("name", Types.STRING)

)
```
除了指定名称和类型外，还可以通过 proctime 和 rowtime 等方法获取外部表数据中的时间属性，也可以通过 from 方法从数据集中根据名称映射 Table Schema 字段信息。
```scala
.withSchema(
  new Schema()
    .field("Field1", Types.SQL_TIMESTAMP).proctime()    // 获取 ProcessTime 属性
    .field("Field2", Types.SQL_TIMESTAMP).rowtime()     // 获取 EventTime 属性
    .field("Field3", Types.BOOLEAN).from("origin_field_name")   // 从 Input/Output 数据指定字段中获取数据

)
```
如果Table API基于Event Time时间概念处理数据，则需要在接入数据中生成事件时间Rowtime信息，以及Watermark的生成逻辑
```scala
.rowtime(
  new Rowtime().timestampFromField("ts_field")    // 根据字段名称从输入数据中提取
  new Rowtime().timestampFromSource()             // 从底层 DataStream API 中转换而来
  new Rowtime().timestampFromExtractor(...)       // 自定义时间抽取器

  // 在Rowtime()对象实例后需要指定Watermark策略
  
  // 延迟 2s 生成 Watermark
  new Rowtime().watermarksPeriodicBounded(2000)
  // 和 rowtime 最大时间保持一致
  new Rowtime().watermarksPeriodicAscending()
  // 使用底层 DataStream API 内建的 Watermark
  new Rowtime().watermarksFromSource()
)
```
### UpdateModes
对于Stream类型的Table数据，需要标记出是由于INSERT、UPDATE、DELETE中的哪种操作更新的数据，在Table API中通过Update Modes指定数据更新的类型，通过指定不同的Update Modes模式来确定是哪种更新操作的数据来与外部系统进行交互

```scala
.connect(...)
.inAppendMode()       // INSERT 操作更新数据
.inUpsertMode()       // INSERT、UPDATE、DELETE 操作更新数据
.inRetractMode()      // INSERT 和 DELETE 操作更新数据
```
Table Connector 实例
```scala
val tableStreamEnv = TableEnvironment.getTableEnvironment(env)

tableStreamEnv
  // 指定需要连接的外部系统
  .connect(new Kafka().version("1.0").topiec("topic").startFromEarliest().property("bootstrap.servers", "localhost:9092"))
  // 指定 Table Format 信息
  .withFormat(new Json().failOnMissingField(true).jsonSchema(""))
  // 指定 Table Schema 信息
  .withSchema(new Schema().field("id", Types.INT).field("name", Types.STRING))
  // 指定数据更新模式为 AppendMode
  .inAppendMode()
  // 注册 TableSource
  .registerTableSource("KafkaInputTable")
```

## 时间概念
对于在Table API和SQL接口中的算子，其中部分需要依赖于时间属性，例如GroupBy Windows类算子等，因此对于这类算子需要在Table Schema中指定时间属性。我们已经Flink支持ProcessTime、EventTime和IngestionTime三种时间概念，针对每种时间概念，Flink Table API中使用Schema中单独的字段来表示时间属性，当时间字段被指定后，就可以在基于时间的操作算子中使用相应的时间属性。

### EventTime 指定

和DataStream API中的一样，Table API中的Event Time也是从输入事件中提取而来的，在Table API中EventTime支持两种提取方式，可以在DataStream转换成Table的过程中指定，也可以在定义TableSource函数中指定

在 DataStream 转换 Table 的过程中定义是通过 .rowtime 来定义，Flink 支持两种方式定义 EventTime 字段，分别是通过在 TableSchema 中自定从 DataStreamEventTime 字段或将 EventTime 字段提前在 DataStream 的某字段中然后通过只能怪相应位置来定义 EventTime 字段。
```scala
inputStream: DataStream[String, String] = ...
// 指定 EventTime 和 Watermarks
val stream: DataStream[(String, String)] = inputStream.assignTimestampsAndWatermarks(...)
// 在 TableSchema 末尾使用 'event_time.rowtime 定义 EventTime 字段，系统会从 TableEnvironment 中获取 EventTime 信息
val table = tableEnv.fromDataStream(stream, 'id, 'val1, 'event_time.rowtime)

// 指定 EventTime 和 Watermark 并在 DataStream 中将第一个字段提取出来指定为 EventTime 字段
val stream: DataStream[(Long, String, String)] = inputStream.assignTimestampsAndWatermarks(...)
val table = tableEnv.fromDataStream(stream, 'event_time.rowtime, 'id, 'var1)
```
当EventTime字段在Table API中定义完毕之后，就可以在基于事件时间的操作算子中使用，例如在窗口中使用方式如下
```scala
val windowTable = table.window(Tumble over 10.minutes on 'event_time as 'window)
```

通过 TableSource 函数定义是指在创建 TableSource 时实现 DefinedRowtimeAttributes 接口来定义 EventTime 字段，然后将定义好的 StreamTableSource 注册到 TableEnvironment 中，然后在 Flink API 应用程序中使用创建好的 Table，并且可以基于 EventTime 属性信息创建时间线管的操作算子。

```scala
class InputEventSource extends StreamTableSource[Row] with DefinedRowtimeAattributes{
    override def getReturnType = {
        val names = Array[String]("id", "value", "envent_time")
        val types = Array[TypeInformation[_]](Types.STRING, Types.STRING, Types.LONG)
        
        Types.ROW(names,types)
    }
    override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row]={
        val intpuStream: DataStream[(String, String, Long)] = ...
        val stream = inputStream.assignTimestampsAndWatermarks(...)
        
        stream
    }
    override def getRowtimeAttributeDescriptors:util.List[RowtimeAttributeDescriptor] ={
        val rowtimeAttrDescr = new RowtimeAttributeDescriptor(
            "event_time",
            new ExistingField("event_time"),
            new AscendingTimestamps
        )
        val rowtimeAttrDescrList = Collections.singletonList(rowtimeAttrDescr)
        
        rowtimeAttrDescrList
    }
}
```

将定义好的StreamTableSource注册到TableEnvironment中之后，然后在FlinkTable API应用程序中使用创建好的Table，并且可以基于EventTime属性信息创建时间相关的操作算子。

```scala
streamEnv.registerTableSource("InputEvent", new InputEventSource)
val windowTable = tableSteam.from("InputEvent").window(Tumle over 10.munites on $"event_time" as $"window")
```

#### ProcessTime 指定

和EventTime时间属性一样，ProcessTime也可以在DataStream转换成Table的过程中定义。和EventTime不同的是， ProcessTime属性只能在Table Schema尾部定义，不能基于指定位置来定义ProcessTime属性。

```scala
val table = tableEnv.fromDataStream(stream, $"id", $"value", $"process_time".proctime)
```

和EventTime一样，也可以在创建TableSource的过程中定义ProcessTime字段，通过实现DefinedProctimeAttribute接口中的getRowtimeAttributeDescriptors方法，创建基于ProcessTime的时间属性信息，并在Table API中注册创建好的Table Source，最后便可以创建基于ProcessTime的操作算子。

```scala
class InputEventSource extends StreamTableSource[Row] with DefinedRowtimeAattributes{
    override def getReturnType = {
        val names = Array[String]("id", "value")
        val types = Array[TypeInformation[_]](Types.STRING, Types.STRING)
        
        Types.ROW(names,types)
    }
    override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row]={
        val stream: DataStream[(String, String, Long)] = ...        
        stream
    }
    override def getProctimeAttribute ={
		"process_time"
    }
}
```

将定义好StreamTableSource注册到TableEnvironment中之后，就能够在FlinkTable API中使用创建好的Table，并可以基于Process Time属性创建Window等基于时间属性的操作算子。以下实例基于process_time创建滚动窗口，然后基于窗口统计结果。

```scala
tableEnv.registerTableSource("inputEnv", new InputEventSource)
```

### Temporal Tables 临时表

在Flink中通过Temporal Tables来表示其实数据元素一直不断变化的历史表，数据会随着时间的变化而发生变化。Temporal Tables底层其实维系了一张Append-Only Table, Flink对数据表的变化进行Track，在查询操作中返回与指定时间点对应的版本的结果。没有临时表时，如果想关联查询某些变化的指标数据，就需要在关联的数据集中通过时间信息将最新的结果筛选出来，显然这种做法需要浪费大量的计算资源。但如果使用临时表，则可直接关联查询临时表，数据会通过不断地更新以保证查询的结果是最新的。Temporal Tables的目的就是简化用户查询语句，加速查询的速度，同时尽可能地降低对状态的使用，因为不需要维护大量的历史数据。

在Flink中，Temporal Tables使用Temporal Table Funciton来定义和表示。一旦Temporal Table Funciton被定义后，每次调用只需要传递时间参数，就可以返回与当前时间节点包含所有已经存在的Key的最新数据集合。定义Temporal TableFunciton需要主键信息和时间属性，其中主键主要用于覆盖数据记录以及确定返回结果，时间属性用于确定数据记录的有效性，用以返回最新的查询数据。

```scala
val tempTableFunction = tempTable.createTemporalTableFunction($"t_proctime", $"t_id")
tableEnv.registerFunction("tempTable", tempTableFunction)
```

Temporal Table Funciton定义好后，就可以在Table API或SQL中使用了。目前，Flink仅支持在Join算子中关联Temporal Table Funciton和Temporal Table。

















































