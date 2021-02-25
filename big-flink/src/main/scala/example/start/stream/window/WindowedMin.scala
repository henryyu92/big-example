package example.start.stream.window

import example.start.stream.SensorReading
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.time.Time

object WindowedMin {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val stream = env.socketTextStream("host", 80).map(data =>{
      val arr = data.split(",")
      SensorReading(arr(0), arr(1).toLong, arr(2).toDouble)
    })

    val resultStream = stream.map(data => (data.id, data.temperature, data.timestamp))
      .keyBy(_._1)
      .timeWindow(Time.seconds(15))
      .reduce((cur, data) => (cur._1, cur._2.min(data._2), data._3))

    resultStream.print

    env.execute("WindowedMin")
  }
}
