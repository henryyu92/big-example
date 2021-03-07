## Flink 监控

Flink任务提交到集群后，接下来就是对任务进行有效的监控。Flink将任务监控指标主要分为系统指标和用户指标两种：系统指标主要包括Flink集群层面的指标，例如CPU负载，各组件内存使用情况等；用户指标主要包括用户在任务中自定义注册的监控指标，用于获取用户的业务状况等信息。

### 系统监控指标

在FlinkUI Overview页签中，包含对系统中的TaskManager、TaskSlots以及Jobs的相关监控指标，用户可通过该页面获取正在执行或取消的任务数，以及任务的启动时间、截止时间、执行周期、JobID、Task实例等指标。

在Task Manager页签中可以获取到每个TaskManager的系统监控指标，例如JVM内存，包括堆外和堆内存的使用情况，以及NetWork中内存的切片数、垃圾回收的次数和持续时间等。

另外除了可以在FlinkUI中获取指标之外，用户也可以使用Flink提供的RestAPI获取监控指标。通过使用http://hostname:8081拼接需要查询的指标以及维度信息，就可以将不同服务和任务中的Metric信息查询出来，其中hostname为JobManager对应的主机名。

```shell
curl http://hostname:8081/jobmanager/metrics
```

### 监控指标注册

除了使用Flink系统自带的监控指标之外，用户也可以自定义监控指标。可以通过在RichFunction中调用getRuntimeContext().getMetricGroup()获取MetricGroup对象，然后将需要监控的指标记录在MetricGroup所支持的Metric中，然后就可以将自定义指标注册到Flink系统中。

#### Counter 指标

Counters指标主要为了对指标进行计数类型的统计，且仅支持Int和Long数据类型。

```scala
class MyMapper extends RichMapFuncton[Long, Long] {
    @transient private var counter: Counter = _
    override def open(parameters: Configuration): Unit = {
        counter = getRuntimeContext()
        	.getMetricGroup()
        	.counter("MyCounter")
    }
    override def map(input: Long): Long = {
        if (input > 0){
            counter.inc()
        }
        value
    }
}
```

#### Guages 指标

Gauges相对于Counters指标更加通用，可以支持任何类型的数据记录和统计，且不限制返回的结果类型。

```scala
class gaugeMapper extends RichMapFunction[String, String] {
    @transient private var countValue = 0
    override def open(parameters: Configuration): Unit = {
        getRuntimeContext().getMetricGroup()
        	.getMetricGroup()
        	.gauge[Int, ScalaGauge[Int]]("MyGauge", ScalaGauge[Int](() => countValue))
    }
    override def map(value: String): String = {
        countValue += 1
        value
    }
}
```

#### Histograms 指标

Histograms指标主要为了计算Long类型监控指标的分布情况，并以直方图的形式展示。Flink中没有默认的Histograms实现类，可以通过引入Codahale/DropWizard Histograms来完成数据分布指标的获取。注意，DropwizardHistogramWrapper包装类并不在Flink默认依赖库中，需要单独引入相关的Maven Dependency。

```scala
class histogramMapper extends RichMapFunction[Long, Long] {
    @transient private var histogram: Histogram = _
    override def open(config: Configuration): Unit = {
        val dropwizardHistogram = new Histogram(new SlidingWindowReservoir(500))
        histogram = getRuntimeContext().getMetricGroup()
        	.histogram("myHistogram", new DropwizardHistogramWrapper(dropwizardHistogram))
    }
    override def map(value: Long): Long = {
        histogram.update(value)
        value
    }
}
```

#### Meters 指标

Meters指标是为了获取平均吞吐量方面的统计，与Histograms指标相同，Flink中也没有提供默认的Meters收集器，需要借助Codahale/DropWizard meters实现，并通过DropwizardMeterWrapper包装类转换成Flink系统内部的Meter。

```scala
class meterMapper extends RichMapFunction[Long, Long] {
    @transient private var meter: Meter = _
    override def open(config: Configuration): Unit = {
        val dropwizardMeter = new Meter()
        meter = getRuntimeContext().getMetricGroup()
        	.meter("myMeter", new DropwizardMeterWrapper(dropwizardMeter))
    }
    override def map(value: Long): Long = {
        meter.markEvent()
        value
    }
}
```

### 监控指标报表

Flink提供了非常丰富的监控指标Reporter，可以将采集到的监控指标推送到外部系统中。通过在conf/flink-conf.yaml中配置外部系统的Reporter，在Flink集群启动的过程中就会将Reporter配置加载到集群环境中，然后就可以把Flink系统中的监控指标输出到Reporter对应的外部监控系统中

在conf/flink-conf.yaml中Reporter的配置包含以下几项内容，其中reporter-name是用户自定义的报表名称，同时reporter-name用以区分不同的reporter。

- metrics.reporter.<reporter-name>.<config>：配置reporter-name对应的报表的参数信息，可以通过指定config名称将参数传递给报表系统，例如配置服务器端口<reporter-name>.port:9090。
- metrics.reporter.<reporter-name>.class：配置reporter-name对应的class名称，对应类依赖库需要已经加载至Flink环境中，例如JMX Reporter对应的是org.apache. flink.metrics.jmx.JMXReporter。
- metrics.reporter.<reporter-name>.interval：配置reporter指标汇报的时间间隔，单位为秒。
- metrics.reporter.<reporter-name>.scope.delimiter：配置reporter监控指标中的范围分隔符，默认为metrics.scope.delimiter对应的分隔符。
- metrics.reporters：默认开户使用的reporter，通过逗号分隔多个reporter，例如reporter1和reporter2。

#### JMX Report 配置

JMX可以跨越一系列异构操作系统平台、系统体系结构和网络传输协议，灵活地开发无缝集成的系统、网络和服务管理应用。目前大多数的应用都支持JMX，主要因为JMX可以为运维监控提供简单可靠的数据接口。Flink在系统内部已经实现JMXReporter，并通过配置就可以使用JMX Reporter输出监控指标到JMX系统中。

```
metrics.reporter.jmx.class: org.apache.flink.metrics.jmx.JMXReporter
metrics.reporter.jmx.port: 8789
```

#### Prometheus Reporter配置

对于使用Prometheus Reporter将监控指标发送到Prometheus中，首先需要在启动集群前将/opt/flink-metrics-prometheus_2.11-1.7.2.jar移到/lib路径下，并在conf/flink-conf. yaml中配置Prometheus Reporter相关信息。PrometheusReporter有两种形式，一种方式是通过配置Prometheus监听端口将监控指标输出到对应端口中，也可以不设定端口信息，默认使用9249，对于多个PrometheusReporter实例，可以使用端口段来设定。

```
metrics.reporter.prometheus.class: org.apche.flink.metrics.prometheus.PrometheusReporter
metrics.reporter.prometheus.port: 9294
```

另外一种方式是使用PrometheusPushGateway，将监控指标发送到指定网关中，然后Prometheus从该网关中拉取数据，对应的Reporter Class为PrometheusPushGateway-Reporter，另外需要指定Pushgateway的Host、端口以及JobName等信息，通过配置deleteOnShutdown来设定Pushgateway是否在关机情况下删除metrics指标。

```
metrics.reporter.promgateway.class: org.apche.flink.metrics.prometheus.PrometheusPushGatewayReporter
metrics.reporter.promgateway.host: localhost
metrics.reporter.promgateway.port: 9091
metrics.reporter.promgateway.jobName: myJob
metrics.reporter.promgateway.randomJobNamSuffix: true
metrics.reporter.promgateway.deleteOnShutdown: false
```

























