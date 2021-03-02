## 函数

在Flink Table API中除了提供大量的内建函数之外，用户也能够实现自定义函数，这样极大地拓展了Table API和SQL的计算表达能力，使得用户能够更加方便灵活地使用Table API或SQL编写Flink应用。

自定义函数主要在TableAPI和SQL中使用，对于DataStream和DataSet API的应用，则无须借助自定义函数实现，只要在相应接口代码中构建计算函数逻辑即可。

通常情况下，用户自定义的函数需要在Flink TableEnvironment中进行注册，然后才能在Table API和SQL中使用。函数注册通过TableEnvironment的registerFunction()方法完成，本质上是将用户自定义好的Function注册到TableEnvironment中的Function CataLog中，每次在调用的过程中直接到CataLog中获取函数信息。Flink目前没有提供持久化注册的接口，因此需要每次在启动应用的时候重新对函数进行注册，且当应用被关闭后，TableEnvironment中已经注册的函数信息将会被清理。

### 标量函数(Scalar Function)

Scalar Function也被称为标量函数，表示对单个输入或者多个输入字段计算后返回一个确定类型的标量值，其返回值类型可以为除TEXT、NTEXT、IMAGE、CURSOR、TIMESTAMP和TABLE类型外的其他所有数据类型。

定义Scalar Function需要继承org.apache.flink.table.functions包中的ScalarFunction类，同时实现类中的evaluation方法，自定义函数计算逻辑需要在该方法中定义，同时该方法必须声明为public且将方法名称定义为eval。同时在一个ScalarFunction实现类中可以定义多个evaluation方法，只需要保证传递进来的参数不相同即可。

```scala
tableEnv.registerTableSource("inputTable", new InputEnvetSource)
val table = tableEnv.from("inputTable")

class Add extends ScalarFunction {
    def eval(a: Int, b: Int): Int = {
        if(a == null || b == null) null
        a + b
    }
    def eval(a: Double, b: Double): Double = {
        if(a == null || b == null) null
        a + b
    }
}

val add = new Add
// Table API 使用无需注册
val result = table.select($"a", $"b", add($"a", $"b"))
// 注册函数
tableEnv.registerFunction("add", new Add)
tableEnv.sqlQuery("select a, b add(a, b) from inputTable")
```

在自定义标量函数过程中，函数的返回值类型必须为标量值，有些比较复杂的数据类型如果Flink不支持获取，需要通过继承并实现ScalarFunction类中的getResultType实现getResult-Type方法对数据类型进行转换。

```scala
object LongToTimestamp extends ScalaFunction {
    def eval(t: Long): Long = {t % 1000}
    override def getResultType(signature: Array[Class[_]]): TypeInforamtion[_] = {
        Types.TIMSTAMP
    }
}
```

### Table Function

Table Function将一个或多个标量字段作为输入参数，且经过计算和处理后返回的是任意数量的记录，不再是单独的一个标量指标，且返回结果中可以含有一列或多列指标，根据自定义Table Funciton函数返回值确定，因此从形式上看更像是Table结构数据。

定义Table Function需要继承org.apache.flink.table.functions包中的TableFunction类，并实现类中的evaluation方法，且所有的自定义函数计算逻辑均在该方法中定义，需要注意方法必须声明为public且名称必须定义为eval。另外在一个TableFunction实现类中可以实现多个evaluation方法，只需要保证参数不相同即可。

在Scala语言Table API中，Table Function可以用在Join、LeftOuterJoin算子中， Table Function相当于产生一张被关联的表，主表中的数据会与TableFunction所有产生的数据进行交叉关联。其中LeftOuterJoin算子当TableFunction产生结果为空时，Table Function产生的字段会被填为空值。

在应用Table Function之前，需要事先在TableEnvironment中注册TableFunction，然后结合LATERAL TABLE关键字使用，根据语句结尾是否增加ONTRUE关键字来区分是Join还是leftOuterJoin操作。

```scala
tableEnv.registerTableSource("inputTable", new InputEventSource)
// Table API 使用自定义函数
val split = new SplitFunction(",")
table.join(split($"origin" as ($"string", $"length",$"hashcode")))
	.select($"origin", $"str", $"length", $"hashcode")
table.leftOuterJoin(split($"origin" as ($"string", $"length", $"hashcode")))
	.select($"origin", $"str", $"length", $"hashcode")
tableEnv.registerFunction("split", new SplitFunction(","))
tableEnv.sqlQuery("select origin, str, length from inputTable, Lateral table(split(origin)) as T(str, length, hashcode)")
// left outer join
tableEnv.sqlQuery("select origin, str, length from inputTable, Lateral table(split(origin)) as T(str, length, hashcode) on true")
```

和Scalar Function一样，对于不支持的输出结果类型，可以通过实现TableFunction接口中的getResultType()对输出结果的数据类型进行转换，具体可以参考ScalarFunciton定义。

### Aggregate Function

Flink Table API中提供了User-Defined Aggregate Functions (UDAGGs)，其主要功能是将一行或多行数据进行聚合然后输出一个标量值，例如在数据集中根据Key求取指定Value的最大值或最小值。

自定义Aggregation Function需要创建Class实现org.apache.flink.table.functions包中的AggregateFunction类。

```scala
public abstract class AggregateFunction<T, ACC> extends UserDefinedFunction {
    public ACC createAccumulator();
    
    public void accumulate(ACC accumulator, )
}
```

















