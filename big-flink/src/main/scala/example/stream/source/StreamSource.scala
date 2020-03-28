package example.stream.source

import java.util.Properties

import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer


object StreamSource {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.addSource(new CustomSource)
  }

  // 集合数据源
  def collectionSource[T](env: StreamExecutionEnvironment, func: _ => Seq[T]): DataStream[T] = {
    val collection = func()
    env.fromCollection(collection)
  }

  // 文件数据源
  def fileSource(env: StreamExecutionEnvironment, fileName: String): DataStream[String]={
    val file = getClass.getClassLoader.getResource(fileName).getFile
    env.readTextFile(file)
  }

  // Kafka 数据源
  def kafkaSource(env: StreamExecutionEnvironment, properties: Properties): DataStream[String]={
    env.addSource(
      new FlinkKafkaConsumer[String]("topic", new SimpleStringSchema(), properties))
  }

}

