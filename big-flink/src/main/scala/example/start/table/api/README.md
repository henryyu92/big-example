## Table

Flink 提供了关系型编程接口 Table API 以及基于 Table API 的 SQL API，使得用户能够通过使用结构化编程接口高效地构建 Flink 应用。

Table API 以及 SQL 能够同一处理批量和实时计算任务，无须切换修改任何代码就能够基于同一套 API 编写流式应用和批量应用，从而达到真正意义地批流统一。


### 数据查询和过滤

对于已经在 TableEnvironment 中注册的数据表，可以通过 from 方法将已经在 CataLog 中注册的表转换成 Table 结构，然后在 Table 上使用 select 操作查询需要获取的字段
```scala
// 使用 as 可以重命名
val result = tableEnv.from("Sensor").select('id, 'var1 as 'myvar1)
```
通过 where 或者 filter 方法可以根据过滤条件过滤数据
```scala
val result = tableEnv.from("Sensor").where('id == "10001")
```

### 窗口操作

Flink API 将窗口分为 GroupBy 窗口和 Over 窗口两种类型。

#### GroupBy Window

GroupBy 窗口和 DataStream 中的窗口一致，都是将流式数据集根据窗口类型切分成有界数据集，然后在有界数据集之上进行聚合类运算。

```scala
val tableStream = TableEnvironment.getTableEnvironment(env)
val sensor = tableStream.scan("Sensors")
// 指定窗口类型并对窗口重命名为 window
val result = sensor.window([w:Window] as $"window")
	.groupBy($"window")	// 窗口聚合，窗口数据会分配到单个 Task 算子中
	.select($"val".sum)	// 指定字段求和
```

在流式计算任务中，如果仅指定 Window 名称，则和Global Window相似，窗口中的数据都会被汇合到一个Task线程中处理，统计窗口全局的结果；如果指定Key和Window名称的组合，则窗口中的数据会分布到并行的算子实例中计算结果

```scala
val tableStream = TableEnvironment.getTableEnvironment(env)
val sensor = tableStream.scan("Sensors")
val result = sensor.window([w:Window] as $"window")
	.groupBy($"window", $"id")
	.select($"id", $"var1".sum)
```

在select语句中除了可以获取数据元素外，还可以获取窗口的元数据信息，例如可以通过window.start获取当前窗口的起始时间，通过window.end获取当前窗口的截止时间（含窗口区间上界），以及通过window.rowtime获取当前窗口截止时间（不含窗口区间上界）

```scala
val result = sensors.window([w:Window] as $"window")
	.groupBy($"window", $"id")
	。select($"id", $"val".sum, $"window".start, $"window".end, $"window".rowtime)
```

在Table API中支持Tumble、Sliding及SessionWindows三种窗口类型，并分别通过不同的Window对象来完成定义。例如Tumbling Windows对应Tumble对象，Sliding Windows对应Slide对象，Session Windows对应Session对象，同时每种对象分别具有和自身窗口类型相关的参数。

```scala
// Tumbling Window
val result = sensor
	// 基于 EventTime 创建滚动窗口
	.window(Tumble over 1.hour on $"rowtime" as $"window")
	// 基于 ProcessTime 创建滚动窗口
	.window(Tumble over 1.hour on $"proctime" as $"window")
	// 基于元素数量创建滚动窗口
	.window(Tumble over 100.rows on $"proctime" as $"window")

// Sliding Window
val result = sensor
	// 基于 EventTime 创建滑动窗口
	.window(Slide over 10.minutes every 5.millis on $"rowtime" as $"window")
	// 基于 ProcessTime 创建滑动窗口
	.window(Slide over 10.minutes every 5.millis on $"proctime" as $"window")
	// 基于元素数量创建滑动窗口
	.window(Slide over 10.rows every 5.rows on $"proctime" as $"window")

// Session Window
val result = sensor
	// 基于 EventTime 创建会话窗口
	.window(Session withGap 10.minutes on $"rowtime" as $"window")
	// 基于 ProcessTime 创建会话窗口
	.window(Session withGap 10.minutes on $"proctime" as $"window")
```



#### Over Window

Over Window和标准SQL中提供的OVER语法功能类似，也是一种数据聚合计算的方式，但和Group Window不同的是，Over Window不需要对输入数据按照窗口大小进行堆叠。Over Window是基于当前数据和其周围邻近范围内的数据进行聚合统计的。

Over Window也是在window方法中指定，但后面不需要和groupby操作符绑定，后面直接接select操作符，并在select操作符中指定需要查询的字段和聚合指。

```scala
val table = sensors
	.window(Over partitionBy $"id" orderBy $"rowtime" preceding UNBOUNDED_RANGE as $"window")
	.select($"id", $"var1".sum over $"window", $"var2".max over $"window")
```

- partitionBy操作符中指定了一个或多个分区字段，Table中的数据会根据指定字段进行分区处理，并各自运行窗口上的聚合算子求取统计结果。需要注意， partitionBy是一个可选项，如果用户不使用partitionBy操作，则数据会在一个Task实例中完成计算，不会并行到多个Tasks中处理。
- orderBy操作符指定了数据排序的字段，通常情况下使用EventTime或Process Time进行时间排序。
- preceding操作符指定了基于当前数据需要向前纳入多少数据作为窗口的范围。preceding中具有两种类型的时间范围，其中一种为Bounded类型，例如指定100.rows表示基于当前数据之前的100条数据；也可以指定10.minutes，表示向前推10min，计算在该时间范围以内的所有数据。另外一种为UnBounded类型，表示从进入系统的第一条数据开始，且UnBounded类型可以使用静态变量UNBOUNDED_RANGE指定，表示以时间为单位的数据范围；也可以使用UNBOUNDED_ROW指定，表示以数据量为单位的数据范围。
- following操作符和preceding相反，following指定了从当前记录开始向后纳入多少数据作为计算的范围。目前Table API还不支持从当前记录开始向后指定多行数据进行窗口统计，可以使用静态变量CURRENT_ROW和CURRENT_RANGE来设定仅包含当前行，默认情况下Flink会根据用户使用窗口间隔是时间还是数量来指定following参数。需要注意的是，preceding和following指定的间隔单位必须一致，也就说二者必须是时间和数量中的一种类型。

```scala
// 创建 UNBOUNDED_RANGE 类型的 OverWindow，指定分区字段为 id 并根据 rowtime 排序
.window([w:OverWindow] as $"window")

// 根据 proctime 排序
.window(Over partitionBy $"id" orderBy $"proctime" preceding UNBOUNDED_RANGE as $"window")

// 创建 UNBOUNDED_ROW 类型的 OverWindow
.window(Over partitionBy $"id" orderBy $"rowtime" preceding UNBOUNDED_ROW as $"window")


// BOUNDED 类型

// 窗口大小为向前 10min
.window(Over partitionBy $"id" orderBy $"rowtime" preceding 10.munites as $"window")

// 根据 ProcessTime 排序
.window(Over partitionBy $"id" orderBy $"proctime" preceding 10.munites as $"window")
// 窗口大小为向前 100 条
.window(Over partitionBy $"id" orderBy $"rowtime" preceding 10.rows as $"window")
```

### 聚合操作

在Flink Table API中提供了基于窗口以及不基于窗口的聚合类操作符基本涵盖了数据处理的绝大多数场景，和SQL中Group By语句相似，都是对相同的key值的数据进行聚合，然后基于聚合数据集之上统计例如sum、count、avg等类型的聚合指标。

#### GroupBy Aggregation

在全量数据集上根据指定字段聚合，首先将相同的key的数据聚合在一起，然后在聚合的数据集上计算统计指标。需要注意的是，这种聚合统计计算依赖状态数据，如果没有时间范围，在流式应用中状态数据根据不同的key及统计方法，将会在计算过程中不断地存储状态数据，所以建议用户尽可能限定统计时间范围避免因为状态体过大导致系统压力过大。

```scala
val groupResult = sensor.groupBy($"id").select($"id", $"var1".sum)
```

#### GroupBy Window Aggregation

该类聚合运算是构建在GroupBy Window之上然后根据指定字段聚合并统计结果。与非窗口统计相比，GroupBy Window可以将数据限定在一定范围内，这样能够有效控制状态数据的存储大小。

```scala
val groupWindowResult = orders.window(Tumble over 1.hour on $"rowtime" as $"window")
	.groupBy($"id", $"window")
	.select($"id", $"window".start, $"window".end, $"window".rowtime, $"var1".sum as $"var1Sum")
```

#### OverWindow Aggregation

和GroupBy Window Aggregation类似，但Over Window Aggregation是构建在Over Window之上，同时不需要在window操作符之后接groupby操作符。

需要注意的是，在select操作符中只能使用一个相同的Window，且Over Window Aggregation仅支持preceding定义的UNBOUNDED和BOUNDED类型窗口，对于following定义的窗口目前不支持。同时OverWindow Aggregation仅支持流式计算场景。

```scala
val overWindowResult = sensor
	.window(Over partitionBy $"id" orderBy $"rowtime" preceding UNBOUNDED_RANGE as $"window")
	.select($"id", $"var1".avg over $"window")
```

#### Distinct Aggregation

Distinct Aggregation和标准SQL中的COUNT(DISTINCT a)语法相似，主要作用是将Aggregation Function应用在不重复的输入元素上，对于重复的指标不再纳入计算范围内。Distinct Aggregation可以与GroupBy Aggregation、GroupByWindow Aggregation及Over Window Aggregation结合使用。

```scala
val groupByDistinctResult = sensor
	.groupBy($"id")
	.select($"id", $"var1".sum.distinct as $"var1Sum")

val groupByWindowDistinctResult = sensor
	.window(Tumble over 1.munites on $"rowtime" as $"window")
	.groupBy($"id", $"window")
	.select($"id", $"var1".sum.distinct as $"var1Sum")

val overWindowDistinctResult = sensor
	.window(Over partitionBy $"id" orderBy $"rowtime" preceding UNBOUNDED_RANGE as $"window")
	.select($"id", $"var1".avg.distinct over $"window")
```

#### Distinct

单个Distinct操作符和标准SQL中的DISTINCT功能一样，用于返回唯一不同的记录。Distinct操作符可以直接应用在Table上，但是需要注意的是，Distinct操作是非常消耗资源的，且仅支持在批量计算场景中使用。

```scala
val distinctResult = sensors.distinct()
```

### 多表关联

Inner Join和标准SQL的JOIN语句功能一样，根据指定条件内关联两张表，并且只返回两个表中具有相同关联字段的记录，同时两张表中不能具有相同的字段名称。

```scala
val t1 = tableEnv.fromDataStream(stream1, $"id1", $"var1", $"var2")
val t2 = tableEnv.fromDataStream(stream2, $"id2", $"var2", $"var4")

val innerJoinResult = t1.join(t2).where($"id1" === $"id2")
	.select($"id1", $"var1", $"var3")
```

Outer Join操作符和标准SQL中的LEFT/RIGHT/FULL OUTER JOIN功能一样，且根据指定条件外关联两张表中不能有相同的字段名称，同时必须至少指定一个关联条件

```scala
val leftOuterResult = t1.leftOuterJoin(t2, $"id1"===$"id2")
	.select($"id1", $"var1", $"var3")

val rightOuterResutl = t1.rightOuterJoin(t2, $"id1"==$"id2")
	.select($"id1", $"var1", $"var3")

val fullOuterResult = t1.fullOuterJoin(t2,  $"id1"===$"id2")
	.select($"id1", $"var1", $"var3")
```

Time-windowed Join是Inner Join的子集，在Inner Join的基础上增加了时间条件，因此在使用Time-windowed Join关联两张表时，需要至少指定一个关联条件以及两张表中的关联时间，且两张表中的时间属性对应的时间概念必须一致（EventTime或者ProcessTime），时间属性对比使用Table API提供的比较符号（<, <=, >=, >），同时可以在条件中增加或者减少时间大小

```scala
val result = t1.join(t2)
	.where($"id1"===$"id2" && $"time1" >= $"time2" - 10.munites && $"time1" < $"time2" + 10.munites)
	.select($"id1", $"var1", $"var3")
```

在Inner Join中可以将Table与自定义的Table Funciton进行关联，Table中的数据记录与Table Fuction输出的数据进行内关联，其中如果Table Function返回空值，则不输出结果

```scala
val upper = new MyUpperUDTF()
val result = table.join(upper($"var1") as $"upperVar1")
	.select($"id1", $"var1", $"var3")
```

Join 操作可以和临时表结合使用

```scala
val temps = tempTable.createTemporalTableFunction($"t_proctime", $"t_id")
val result = table.join(temps($"o_rowtime"), $"table_key" == $"temp_key")
```

### 集合操作

当两张Table都具有相同的Schema结构，则这两张表就可以进行类似于Union类型的集合操作。

除了UnionAll和In两个操作符同时支持流计算场景和批量计算场景之外，其余的操作符都仅支持批量计算场景。

```scala
val t1 = tableEnv.fromDataSet(dataset1, $"id1", $"var1", $"var2")
val t2 = tableEnv.fromDataSet(dataset2, $"id2", $"var3", $"var4")

// Union
val unionTable = t1.union(t2)

// UnionAll
val unionAllTable = t1.unionAll(t2)

// Intersect，返回交集，不包含重复记录
val intersectTable = t1.intersect(t2)

// InsersectAll，返回交集，包含重复记录
val intersectAllTable = t1.intersectAll(t2)

// Minus 返回差集
val minusTable = t1.minus(t2)

// minusAll
val minusAllTable = t1.minusAll(t2)

// In
val inTable = t1.where($"id" in(t2))
```

### 排序操作

Orderby操作符根据指定的字段对Table进行全局排序，支持顺序（asc）和逆序（desc）两种方式。可以使用Offset操作符来控制排序结果输出的偏移量，使用fetch操作符来控制排序结果输出的条数。需要注意，该操作符仅支持批量计算场景。

```scala
val result = table.orderBy($"var1".asc)
// 返回前 5 条
val result2 = table.orderBy($"var1".desc.fetch(5))
// 返回忽略前5 条后的数据
val result3 = table.orderBy($"var1".desc.offset(5))
// 返回忽略前 5 条后剩余数据的前 5 条
val result4 = table.orderBy($"var1".desc.offset(5).fetch(5))
```

### 数据写入

通过Insert Into操作符将查询出来的Table写入注册在TableEnvironment的表中，从而完成数据的输出。注意，目标表的Schema结构必须和查询出来的Table的Schema结构一致。

```scala
table.insertInto("outer-table")
```



### 自定义数据源

Flink Table API可以支持很多种数据源的接入，除了能够使用已经定义好的TableSource数据源之外，用户也可以通过自定义TableSource完成从其他外部数据介质（数据库，消息中间件等）中接入流式或批量类型的数据。Table Source在TableEnviroment中定义好后，就能够在Table API和SQL中直接使用。

与Table Source相似的在Table API中提供通过TableSink接口定义对Flink中数据的输出操作，用户实现TableSink接口并在TableEnvironment中注册，就能够在Table API和SQL中获取TableSink对应的Table，然后将数据输出到TableSink对应的存储介质中。

#### Table Source 定义

TableSource是在Table API中专门针对获取外部数据提出的通用数据源接口。TableSource定义中将数据源分为两类，一种为StreamTableSource，主要对应流式数据源的数据接入；另外一种BatchTableSource，主要对应批量数据源的数据接入。

```scala
TableSource<T>{
    // 指定数据源的 TableSchema 信息
    public TableSchema getTableSchema();
    // 返回数据源中的字段数据类型信息
    public TypeInformation<T> getReturnType();
    // 返回 TableSource 描述信息
    public String explainSource();
}
```

#### StreamTableSource

在StreamTableSource中可以通过getDataStream方法将数据源从外部介质中抽取出来并转换成DataStream数据集，且对应的数据类型必须是TableSource接口中getReturnType方法中返回的数据类型。StreamTableSource可以看成是对DataStream API中SourceFunciton的封装，并且在转换成Table的过程中增加了Schema信息。

```scala
class InputEventSource extend StreamTableSource[Row]{
    override def getReturnType = {
        val names = Array[String]("id", "value")
        val types = Array[TypeInformation[_]](Types.STRING, Types.LONG)
        Types.ROW(names, types)
    }
    override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
        val inputStream: DataStream[(String, Long)] = execEnv.addSource(...)
        val stream: DataStream[Row] = inputStream.map(t => Row.of(t._1, t._2))
        stream
    }
    override def getTableSchema: TableSchema = {
        val names = Array[String]("id", "value")
        val types = Array[TypeInformation[_]](Types.STRING, Types.LONG)
        new TableSchema(names, types)
    }
}
```

定义好的StreamTableSource之后就可以在Table API和SQL中使用，在Table API中通过registerTableSource方法将定义好的TableSource注册到TableEnvironment中，然后就可以使用scan操作符从TableEnvironment中获取Table在SQL中则直接通过表名引用注册好的Table即可。

```scala
tableEnv.registerTableSource("inputTable", new InputEventSource)
val table = tableEnv.from("inputTable")
```

#### BatchTableSource

BatchTableSource接口具有了getDataSet()方法，主要将外部系统中的数据读取并转换成DataSet数据集，然后基于对DataSet数据集进行处理和转换，生成BatchTableSource需要的数据类型。其中DataSet数据集中的数据格式也必须要和TableSource中getReturnType返回的数据类型一致。

```scala
class InputBatchSource extend BatchTableSource[Row]{
    override def getReturnType = {
        val names = Array[String]("id", "value")
        val types = Array[TypeInformation[_]](Types.STRING, Types.LONG)
        Types.ROW(names, types)
    }
    override def getDataSet(execEnv: ExecutionEnvironment): DataSet[Row] = {
        val inputDataSet = execEnv.createInput(...)
        val dataSet: DataSet[Row] = inputDataSet.map(t => Row.of(t._1, t._2))
        dataSet
    }
    override def getTableSchema: TableSchema = {
        val names = Array[String]("id", "value")
        val types = Array[TypeInformation[_]](Types.STRING, Types.LONG)
        new TableSchema(names, types)
    }
}
```

#### StreamTableSink

可以通过实现StreamTableSink接口将Table中的数据以流的形式输出。在Stream-TableSink接口中emitDataStream方法定义了Table中输出数据的逻辑，实际是将Data-Stream数据集发送到对应的存储系统中。另外根据Table中数据记录更新的方式不同，将StreamTableSink分为AppendStreamTableSink、RetractStreamTableSink以及Upsert StreamTableSink三种类型。

##### AppendStreamTableSink

AppendStreamTableSink只输出在Table中所有由于INSERT操作所更新的记录，对于类似于DELTE操作更新的记录则不输出。

##### RetractStreamTableSink

RetractStreamTableSink同时输出INSERT和DELETE操作更新的记录，输出结果会被转换为Tuple2< Boolean, T>的格式。其中，Boolean类型字段用于对结果进行标记，如果是INSERT操作更新的记录则标记为true，反之DELETE操作更新的记录则标记为false；第二个字段为具体的输出数据。

##### UpsertStreamTableSink

该接口能够输出INSERT、UPDATE、DELETE三种操作更新的记录。使用UpsertStreamTableSink接口，需要指定输出相应的唯一主键keyFields，可以是单个字段的或者多个字段的组合，如果KeyFields不唯一且AppendOnly为false时，该接口中的方法会抛出TableException。

#### BatchTableSink

BatchTableSink接口主要用于对批量数据的输出，和StreamTableSink不同的是，该接口底层操作的是DataSet数据集。BatchTableSink中没有区分是INSERT还是DELETE等操作更新的数据，而是全部都统一输出。

### TableFactory

TableFactory主要作用是将事先定义好的TableSource和TableSink实现类封装成不同的Factory，然后通过字符参数进行配置，这样在Table API或SQL中就可以使用配置参数来引用定义好的数据源。TableFactory使用Java的SPI机制为TableFactory来寻找接口实现类，因此需要保证在META-INF/services/资源目录中包含所有与TableFactory实现类对应的配置列表，所有的TableFactory实现类都将被加载到Classpath中，然后应用中就能够通过使用TableFactory来读取输出数据集在Flink集群启动过程。

```scala
class SocketTableSourceFactory extends StreamTableSourceFactory[Row]{
    // 指定 TableFactory 上下文参数，Flink应用中配置参数需要和Context具有相同的Key-Value参数才能够匹配到TableSource并使用，否则不会匹配相应的TableFactory实现类
    override def requiredContext(): util.Map[String, String] = {
        val context = new util.HashMap[String, String]()
        context.put("update-mode", "append")
        context.put("connector.type", "dev-system")
        context
    }
    // 指定 TableFactory 用于处理的参数集合，如果Flink应用中配置的参数不属于当前的TableFactory，便会抛出异常
    override def supportedProperties():util.List[String] = {
        val properties = new util.ArrayList[String]()
        properties.add("connector.host")
        properties.add("connector.port")
        properties
    }
    
    override def createStreamTableSource(properties: util.Map[String, String]): StreamTableSource[Row] = {
        val socketHost = properties.get("connector.host")
        val socketPort = properties.get("connector.port")
        new SocketTableSource(socketHost, socketPort)
    }
}
```

SQL Client能够支持用户在客户端中使用SQL编写Flink应用，从而可以在SQLClient中查询记录实现和定义好的SocketTableSource中数据的数据源。

```yaml
tables:
- name: SocketTable
  type: source
  update-mode: append
  connector:
    type: dev-system
    host: localhost
    port: 10000
```

配置文件通过Yaml文件格式进行配置，需要放置在SQL Client environment文件中，文件中的配置项将直接被转换为扁平的字符配置，然后传输给相应的TableFactory。注意，需要将对应的TableFactory事先注册至Flink执行环境中，然后才能将配置文件项传递给对应的TableFactory，进而完成数据源的构建。



如果用户想在Table & SQL API中使用TableFactory定义的数据源，也需要将对应的配置项传递给对应的TableFactory。为了安全起见，Flink提供了ConnectorDescriptor接口让用户定义连接参数，然后转换成字符配置项。

```scala
class MySocketConnector(host: String, port: String) extends ConnectorDescriptor("dev-system", 1, false) {
    override protected def toConnectorProperties(): Map[String, String] = {
        val properties = new HashMap[String, String]
        properties.put("connector.host", host)
        properties.put("connector.port", port)
        properties
    }
}
```

创建MySocketConnector之后，在Table & SQL API中通过ConnectorDescriptor连接Connector，然后调用registerTableSource将对应的TableSource注册到TableEnvironment中。接下来就可以在Table & SQL API中正式使用注册的Table，以完成后续的数据处理了。

```scala
tableEnv.connect(new MySocketConnector("localhost", "10000"))
	.inappendMode()
	.registerTableASource("mySocketTable")
```