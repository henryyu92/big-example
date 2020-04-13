## Flink 编程模型
Flink 将数据处理(流处理/批处理)抽象成四层：
- ```Stateful Stream Processing```：处理有状态的流(stateful streaming)的最底层的抽象，通过 Process Function 和 DataStream API 结合，可以实现非常复杂的逻辑。
- ```DataStream/DataSet API```：Flink 提供的核心 API，提供了流处理(DataStream API)和批处理(DataSet API)的多个操作接口，是最常用的 API。
- ```Table API```：
- ```SQL```：

Flink 应用程序包含 5步：设定执行环境、指定数据源(source)、转换数据集(transform)、指定输出(sink)。

### 执行环境  
Flink 执行环境决定了程序运行的环境(本地或者集群)，也决定了应用的类型。```StreamExecutionEnvironment``` 是流式数据处理环境，```ExecutionEnvironment``` 是批数据处理环境。

Flink 提供了三种获取执行环境的方式：
- ```createLocalEnvironment```：创建本地运行环境，使用多线程的方式运行任务，默认的并行度是机器的核心数
- ```createRemoteEnvironment```：创建远程集群运行环境，需要指定 JobManager Master 的 IP 和端口，以及应用程序使用到额外的 JAR 地址(集群可访问)
- ```getExecutionEnvironment```：如果在本地运行则创建本地运行环境，如果在集群运行则创建集群运行环境

指定 JobManager 创建远程运行环境时，通过指定应用程序所在的 Jar 包将程序拷贝到 JobManager 节点上，然后将 Flink 应用程序运行在远程的环境中，本地程序相当于一个客户端。


### 指定数据源
ExecutionEnvironment 提供了不同的数据接入接口完成数据的初始化，将外部数据转换为 DataStream(流处理数据集) 或 DataSet(批处理处理集)。Flink 提供了多种从外部读取数据的连接器，包括批量和实时的数据连接器能够将 Flink 系统和其他第三方系统连接直接获取外部数据。
#### 数据集的转换计算
数据从外部系统读取并转换成 DataStream 或者 DataSet 数据集后，需要对数据集作各种转换计算操作。Flink 中的 Transformation 操作都是通过不同的 Operator 来实现，每个 Operator 内部通过实现 Function 接口完成数据的处理逻辑定义。DataStream 和 DataSet 提供了大量的转换算子，只需要定义算子执行的逻辑函数然后应用在数据转换操作 Operator 接口中即可。

在 DataStream 数据经过不同的算子转换过程中，有些算子需要根据指定的 key 进行转换，需要先将 DataStream 或 DataSet 转换成对应的 KeyedStream 和 GroupedDataSet 将相同的 key 值的数据路由到相同的 pipline 中然后进行下一步的计算操作。分区 key 可以通过三种方式指定：
- 根据字段位置指定：在 DataStream 中通过 keyBy 方法将 DataStream 数据集根据指定的 key 转换成重新分区的 KeyedStream。在 DataSet 中对数据根据某一条件进行聚合的时候，也需要对数据进行重新分区。
- 根据字段名称指定：keyBy 方法和 GroupBy 的 key 除了能够通过字段位置来指定外，还可以根据字段名来指定。使用字段名称需要 DataStream 或者 DataSet 中的数据结构必须是 Tuple 或者 POJO 类型。
- 通过 key 选择器指定：Flink 还提供了根据 KeySelector 接口用于指定聚合的 key，通过自定义类实现 KeySelector 并重写 getKey 方法可以自定义指定聚合的 key
```
// 通过位置指定
// DataStream 中根据第一个字段重新分区，然后根据第二个字段聚合
val result = dataStream.keyBy(0).sum(1)
// DataSet 中需要根据根据第一个字段进行重分区
val grouped = dataSet.groupBy(0).groupedDataStream.max(1)

// 通过字段名指定
personDataStream.keyBy("_1").sum(1)
personDataSet.groupBy("name").max(1)

// 通过 KeySelector 指定
personDataStream.keyBy(new KeySelector[Person, String](){
  def getKey(person: Person):String = person.name
})
```
#### 指定计算结果输出
数据集经过转换和计算之后形成最终的数据集需要输出，可以将计算结果输出到文件系统或者打印在控制台。Flink 提供了大量的 Connector 和外部系统的交互，可以直接通过调用 ```addSink``` 添加输出系统定义的 DataSink 类算子输出到外部系统。
#### 触发程序执行
计算逻辑定义好之后调用 ```ExecutionEnvironment#execute()``` 触发程序的执行，返回的 ```JobExecutionResult``` 包含程序执行时间和类机器等运行指标。**注意 DataStream 流式应用需要显式调用 execute 方法提交，而 DataSet 批处理应用不需要显式调用 execute 方法(输出算子已经包含)。**
### Flink 数据类型
#### 数据类型支持
Flink 支持的数据类型的描述信息都是由 TypeInformation 定义，主要作用是为了在 Flink 系统内有效地对数据结构类型进行管理，能够在分布式计算过程中对数据的类型进行管理和推断并进行了相应的性能优化。另外使用 TypeInfomation 管理数据类型信息能够在数据处理之前将数据类型推断出来，而不是真正在触发计算后才识别出，这样能够及时有效避免使用 Flink 编写应用的过程中的数据类型问题。
- 原生数据类型：Flink 通过 BasicTypeInfo 能够支持任意 Java 原生基本数据类型和 String 类型；通过 BasicArrayTypeInfo 支持基本数据类型数组和 String 类型数组。
- Tuple 类型：Flink 通过 TupleTypeInfo 支持 Tuple 数据类型，不支持空值存储，字段上限为 25。
- Case Class 类型：Flink 通过 CaseClassTypeInfo 支持任意的 Scala Case Class 类型包括 Scala tuples 类型，支持字段上限为 22，支持通过字段名称和位置索引获取，不支持空值存储。
- POJO 类型：Flink 通过 PojoTypeInfo 描述任意 Java 和 Scala 类，可以通过字段名称获取字段。如果在 Flink 中使用 POJO 则需要遵循以下要求：
  - POJO 类必须是 public 修饰且必须独立定义，不能是内部类
  - POJO 类必须包含有默认的空参构造器
  - POJO 类中所有的 field 必须是 public 或者具有 public 修饰的 getter 和 setter
  - POJO 类中的字段类型必须是 Flink 支持的
- Value 类型：Value 数据类型实现了 ```org.apache.flink.types.Value```，目前 Flink 内建的 Value 类型有 IntValue, DoubleValue 和 StringValue，可以结合原生数据类型和 Value 类型一起使用
- 特殊数据类型：Flink 支持一些特殊的数据类型，如 List, Map, Either, Option, Try 以及 Writable 等，这些数据类型无法根据字段位置或者名称获取字段信息。
#### TypeInfomation 获取
##### Scala API 类型信息
Scala 通过使用 Manifest 和类标签在编译器运行时获取类型信息，因而不会有类型擦除的问题。同时 Flink 使用了 Scala Macros 框架在编译代码的过程中推断函数输入参数和返回值的类型信息并注册成 TypeInformation 以支持上层计算算子使用。

使用 Scala 开发 Flink 应用时如果使用到 Flink 已经通过 TypeInformation 定义的数据类型则只需要使用隐式参数的方式引入：
```
import org.apache.flink.api.scala._
```
##### Java API 类型信息
由于 Java 的泛型会出现类型擦除问题，因此 Flink 通过反射尽可能的重构类型信息。如果输出结果的类型依赖于输入参数的类型则
##### 自定义 TypeInfomation