package example.stream

import org.apache.flink.streaming.api.scala._


object WordCount {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    env.socketTextStream("127.0.0.1", 7777)
      .flatMap(_.split(" ")).setParallelism(1)
      .map((_, 1))
      .keyBy(0)
      .sum(1)
      .print

    env.execute("stream word count")

  }
}
