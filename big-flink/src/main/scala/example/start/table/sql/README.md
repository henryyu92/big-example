## SQL

Flink SQL底层使用ApacheCalcite框架，将标准的Flink SQL语句解析并转换成底层的算子处理逻辑，并在转换过程中基于语法规则层面进行性能优化。

使用SQL编写Flink应用时，能够屏蔽底层技术细节，能够更加方便且高效地通过SQL语句来构建Flink应用。Flink SQL构建在Table API之上，并含盖了大部分的Table API功能特性。同时Flink SQL可以和Table API混用，Flink最终会在整体上将代码合并在同一套代码逻辑中，另外构建一套SQL代码可以同时应用在相同数据结构的流式计算场景和批量计算场景上，不需要用户对SQL语句做任何调整，最终达到实现批流统一的目的。

### 执行 SQL

Flink SQL可以借助于TableEnvironment的SqlQuery和SqlUpdate两种操作符使用，前者主要是从执行的Table中查询并处理数据生成新的Table，后者是通过SQL语句将查询的结果写入到注册的表中。其中SqlQuery方法中可以直接通过$符号引用Table，也可以事先在TableEnvironment中注册Table，然后在SQL中使用表名引用Table。

```scala
// 在 SQL 中引用 Table
val env = StreamExecutionEnvironment.getExecutionEnvironment
val tableEnv = TableEnvironment.getTableEnvironment(env)
val inputStream:DataStream[(Long, String, Int)] = ...
val sensor_table = inputStream.toTable(tableEnv, $"id", $"type", $"var1")
// 在 SqlQuery 中直接使用 $ 引用创建好的 Table
val result = tableEnv.sqlQuery(s"select sum(var1) from $sensor_table where product === 'perature'")

// 在 SQL 中引用注册表
tableEnv.registerDataSteam("sensors", ds, $"user", $"product", $"amount")
// 直接引用注册的表
val result = tableEnv.sqlQuery(s"select product, amout from sensors where type like 'temperature'")
```

### 数据输出

可以调用sqlUpdate()方法将查询出来的数据输出到外部系统中，首先通过实现TableSink接口创建外部系统对应的TableSink，然后将创建好的TableSink实例注册在TableEnvironment中

```scala
val csvTableSink = new CsvTableSink("path/csvfile")
val fieldNames = Array("id", "type")
val fieldTypes = Array(Types.Long, Types.STRING)
// 注册 TableSink
tableEnv.registerTableSink("csv_output_table", fieldNames, fieldTypes, csvSink)
tableEnv.sqlUpdate("insert into csv_output_table select id, type from sensors where type = 'temperature'")
```

### 数据过滤与查询

可以通过Select语句查询表中的数据，并使用Where语句设定过滤条件，将符合条件的数据筛选出来。

```scala
select * from sensors where id % 2 = 0
```

### Group Windows

Group Window是和GroupBy语句绑定使用的窗口，和Table API一样，FlinkSQL也支持三种窗口类型，分别为Tumble Windows、HOP Windows和SessionWindows，其中HOP Windows对应Table API中的Sliding Window，同时每种窗口分别有相应的使用场景和方法。

```scala
val env = StreamExecutionEnvironment.getExecutionEnvironment
val tableEnv = TableEnvironment.getTableEnvironment(env)
val ds: DataStream[(Long, String, Int)] = ...
talbeEnv.registerDataStream("sensors", ds, $"id", $"type", $"var1", $"proctime".proctime, $"rowtime".rowtime)

// Tumble Windows

// 基于 proctime 创建 Tumble 窗口，根据 id 聚合
tableEnv.sqlQuery("select id, sum(var1) from sensors group by tumble(proctime, interval '10' minute), id")
// 基于 rowtime 创建 Tumble 窗口
tableEnv.sqlQuery("select id, sum(var1) from sensors group by tumble(rowtime, interval '10' minute), id")

// Hop Windows

// 基于 proctime 创建滑动窗口，长度为 10m，每次滑动 1m
tableEnv.sqlQuery("select id, sum(var1) from sensors group by hop(proctime, interval '1' minute, interval '10' minute), id")

// hop_start	窗口起始位置
// hop_end		窗口结束位置
tableEnv.sqlQuery("select id, 
	hop_start(rowtime, interval '5' minute, interval '10' minute) as wStart, 
	hop_end(rowtime, interval '5' minute, interval '10' minute) as wEnd,
	sum(var1)
	from sensors group by hop(rowtime, interval '5' minute, interval '10' minute), id")

// Session Window

// 基于 proctime 创建 Session 窗口，指定 Session Gap 为 1h
tableEnv.sqlQuery("select id, sum(var1) from sensors group by session(proctime, interval '1' hour), id")
// session_start		session 窗口开始时间
// session_end			session 窗口结束时间
tableEnv.sqlQuery("select id,
	session_start(proctime, interval '5' hour) as wStart,
	session_end(proctime, interal '5' hour) as wEnd,
	sum(var1) from sensors
	group by session(rowtime, interval '5' hour), id")
```

### 数据聚合

#### GroupBy Aggregation

GroupBy Aggregation在全景数据集上根据指定字段聚合，产生计算指标。需要注意的是，这种聚合统计计算主要依赖于状态数据，如果不指定时间范围，对于流式应用来说，状态数据会越来越大，所以建议用户尽可能在流式场景中使用GroupBy Aggregation。

```sql
select id, sum(var1) as d from sensors group by id
```

#### GroupBy Window Aggregation

GroupBy WindowAggregation基于窗口上的统计指定key的聚合结果，在FlinkSQL中通过在窗口之前使用GroupBy语句来定义。

```sql
select id, sum(var1) from sensors group by tumble(rowtime, interval '1' day), user

select id, min(var1) from sensors group by hop(rowtime, interval '1' hour), id

select id, max(var1) from sensors group by session(rowtime, interval '1' day), id
```

#### Over Window Aggregation

Over Window Aggregation基于Over Window来计算聚合结果，可以使用Over关键字在查询语句中定义Over Window，也可以使用Window w AS()方式定义，在查询语句中使用定义好的window名称。注意Over Window所有的聚合算子必须指定相同的窗口，且窗口的数据范围目前仅支持PRECEDING到CURRENT ROW，不支持FOLLOWING语句。

```sql
select max(var1) 
// 通过 over 定义 over window
over(
    partition by id
    order by proctime
    // 数据限定在从当前数据向前推 10 条记录到当前数据
    rows between 10 preceding and current row
) from sensors

select count(var1) over window, sum(var1) over window from sensors
Window window as (
    partition by id
    order by proctime
    rows between 10 preceding and current row
)
```

### Distinct

Distinct用于返回唯一不同的记录。下面的代码通过Distinct关键子查询数据表中唯一不同的type字段。

```sql
select distinct type from sensors
```

### Grouping sets

Grouping sets将不同Key的GROUP BY结果集进行UNION ALL操作

```sql
select sum(var1) from sensors group by grouping sets((id), (type))
```

### Having

Flink SQL中Having主要解决WHERE关键字无法与合计函数一起使用的问题，因此可以使用HAVING语句来对聚合结果筛选输出。

```sql
select sum(var1) from sensors group by id having sum(var1) > 5
```

### 多表关联

Inner Join通过指定条件对两张表进行内关联，当且仅当两张表中都具有相同的key才会返回结果。

SQL外连接包括LEFT JOIN、RIGHT JOIN以及FULL OUTER三种类型，和标准的SQL语法一致，目前Flink SQL仅支持等值连接，不支持任意比较关系的theta连接。

和Inner Join类似，Time-windowed Join在Inner Join的基础上增加了时间属性条件，因此在使用Time-windowed Join关联两张表时，需要至少指定一个关联条件以及绑定两张表中的关联时间字段，且两张表中的时间属性对应的时间概念需要一致（Event Time或者ProcessTime），其中时间比较操作使用SQL中提供的比较符号（<, <=, >=, >），且可以在条件中增加或者减少时间间隔。

```sql
-- Inner Join
select * from sensors inner join sensor_detail on sensors.id = sensor_detail.id

--Outer Join
select * from sensors left join sensor_detail on sensors.id = sensor_detail.id
select * from sensors right join sensor_detail on sensors.id = sensor_detail.id
select * from sensors full outer join sensor_detail on sensors.id = sensor_detail.id

--Time-windowed Join
select * from sensors_1 a, sensors_2 b where a.id = b.id and a.rowtime between s.rowtime_interval '4' hour and s.rowtime
```

在Inner Join或Left outer Join中可以使用自定义Table Funciton作为关联数据源，原始Table中的数据和Table Fuction产生的数据集进行关联，然后生成关联结果数据集。Flink SQL中提供了LATERAL TABLE语法专门应用在Table Funciton以及Table Funciton产生的临时表上，如果Table Function返回空值，则不输出结果。

```sql
select id, tag from sensors, lateral table(my_udtf(type)) t as t
```

在Flink中临时表借助于Table Function生成，可以通过Flink LATERAL TABLE语法使用自定义的UDTF函数产生临时数据表的，并指定关联条件与临时表进行关联。目前临时表仅支持内关联操作，其他关联操作目前不支持

```sql
select id, type from sensors, lateral table(my_udtf(o_proctime)) where type = type2
```

### 集合操作

union 合并两张表同时去除相同的记录，注意两张表的表结构必须要一致，且该操作仅支持批量计算场景。

```sql
select * from (
    (select user from sensors where var1 >= 0)
	union
	(select user from sensors where type = 'temperature')
)
```

unionAll 合并两张表但不去除相同的记录，要求两张表的表结构必须一致。

```scala
select *
from (
	(select user from sensors where var1 >= 0)
	union All
	(select user from sensors where type = 'temperature')
)
```

INTERSECT / EXCEPT：和标准SQL中的INTERSECT语句功能相似，合并两张变且仅返回两张表中的交集数据，如果记录重复则只返回一条记录。目前该操作仅支持批量计算场景。

```sql
select *
from (
	(select user from sensors where id%2 = 0)
    intersect
    (select user from sensors where type = 'temperature')
)
```

in 通过子查询表达式判断左表中记录的某一列是否在右表中，如果在则返回True，如果不在则返回False, where语句根据返回条件判断是否返回记录。

```sql
select id, type from sensors where type in (
	select type from sensor_types
)
```

exisits 用于检查子查询是否至少返回一行数据，该子查询实际上并不返回任何数据，而是返回值True或False。

```scala
select id, type from sensors where type exists (
    select type from sensor_types
)
```

### 数据输出

通过INSERT Into语句将Table中的数据输出到外部介质中，需要事先将输出表注册在TableEnvironment中，查询语句对应的Schema和输出表结构必须一致。同时需要注意Insert Into语句只能被应用在SqlUpdate方法中，用于完成对Table中数据的输出。

```sql
insert into output table select id, type from sensors
```