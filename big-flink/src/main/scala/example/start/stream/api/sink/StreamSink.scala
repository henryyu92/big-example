package example.start.stream.api.sink

import java.util.Properties

import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.scala.DataStream
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer


object StreamSink {

  def main(args: Array[String]): Unit = {

  }


  def kafkaSink(stream: DataStream[String], properties: Properties): Unit = {

    stream.addSink(new FlinkKafkaProducer[String]("", new SimpleStringSchema(), properties))
  }
}
