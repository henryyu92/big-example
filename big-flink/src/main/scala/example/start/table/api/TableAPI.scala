package example.start.table.api

import org.apache.flink.streaming.api.scala._
import org.apache.flink.table.api.scala._

object TableAPI {

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tableEnv = StreamTableEnvironment.create(env)

    val dataStream = env.fromElements(("a", 1))

    val table = tableEnv.fromDataStream(dataStream)

    val result = table.select("id").filter("id == 'sensor_1'")

    result.toAppendStream[(String, Int)].print

    env.execute("table api")
  }
}
