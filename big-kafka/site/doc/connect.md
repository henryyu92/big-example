### kafka connect
Kafka Connector 是一个工具用于在 Kafka 和外部数据存储系统之间移动数据。
Kafka Connector 核心概念：
- Source：Source 负责导入数据到 Kafka
- Sink：Sink 负责从 Kafka 导出数据
- Task：Task 是 Kafka Connector 的基本任务单元，Connector 将一项任务切分为多个 Task 然后分发到各个 Worker 进程中去执行
- Workder：Worker 是 Kafka Connector 的执行单元，所有的 Task 必须在 Worker 进程中运行

#### 独立模式
Kafka 中的 ```connect-standalone.sh``` 脚本用于实现独立模式运行 Kafka Connect。在独立模式下，所有的操作都是在一个进程中完成，无法利用 Kafka 自身提供的负载均衡和高容错等特性。

```connect-standalone.sh``` 脚本使用时需要指定两个配置文件：一个用于 Worker 进程运行的相关配置文件；一个指定 Source 连接器或 Sink 连接器的配置文件，可以同时指定多个连接器配置，每个连接器配置文件对应一个连接器，每个连接器名全局唯一
Source 连接器的用法：
- 修改用于 Worker 进程运行的配置文件($KAFKA_HOME/config/connect-statndalone.properties)
```properties
# 配置 Kafka 集群地址
bootstrap.servers=localhost:9092
# 配置 key 和 value 的格式转化类
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
# 指定 Json 消息中可以包含 schema
key.converter.schemas.enable=true
value.converter.schemas.enable=true
# 指定保存偏移量的文件路径
offset.storage.file.filename=/tmp/connect.offsets
# 提交指定偏移量的频率
offset.flush.interval.ms=10000
```
- 修改 Source 连接器的配置文件($KAFKA_HOME/config/connect-file-source.properties)
```properties
# 配置连接器的名称
name=local-file-sink
# 配置连接器类的全限定名
connector.class=FileStreamSink
# 指定 Task 的数据量
tasks.max=1
# 指定连接器数据源文件路径
file=test.sink.txt
# 设置连接器数据导入的主题，如果不存在则会自动创建
topics=connect-test
```
- 启动连接器
```shell
bin/connect-standalone.sh config/connect-statndalone.properties config/connect-file-source.properties
```
Sink 连接器的使用：
- 修改 connect-standalone.properties
```properties
bootstrap.servers=localhost:9092
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.storage.StringConverter
key.converter.schemas.enable=true
value.converter.schemas.enable=true
offset.storage.file.filename=/tmp/connect.offsets
offset.flush.interval.ms=10000
```
- 配置 Sink 连接器的配置文件($KAFKA_HOME/config/connect-file-sink.properties)
```properties
name=local-file-sink
connector.class=FileStreamSink
tasks.max=1
file=test.sink.txt
topics=connect-test
```
- 启动 Sink 连接器
```shell
bin/connect-standalone.sh config/connect-standalone.properties config/connect-file-sink.properties
```
#### REST API
可以使用 Kafka Connector 提供的基于 REST 风格的 API 接口来管理连接器，默认端口为 8083，可以通过 Worker 进程的配置文件中的 rest.port 参数来修改端口号
|API|作用|
|-|-|
|GET /|查看 Kafka 集群版本信息|
|GET /connectors|-|
|POST /connectors|-|
|GET /connectors/{name}|-|
|GET /connectors/{name}/config|-|
|PUT /connectors/{name}/config|-|
#### 分布式模式