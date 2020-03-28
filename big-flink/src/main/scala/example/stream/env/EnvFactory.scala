package example.stream.env

import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment


object EnvFactory {

  def main(args: Array[String]): Unit = {

    StreamExecutionEnvironment.createLocalEnvironment()

  }

}
