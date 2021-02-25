package example.start.stream.state

import example.start.stream.SensorReading
import org.apache.flink.api.common.functions.RichFlatMapFunction
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector

/**
 * 相邻温度间隔超过阈值则报警
 */
object TemperatureChangeAlert {
  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val dataStream = env.socketTextStream("localhost", 9999)
      .map(data => {
        val arr = data.split("")
        SensorReading(arr(0), arr(1).toLong, arr(2).toDouble)
      })

    dataStream
      .keyBy(_.id)
//      .flatMap(new RichFlatMapFunction[SensorReading, (String, Double, Double)] {
//
//        lazy val lateTempState = getRuntimeContext.getState(new ValueStateDescriptor[Double]("last temperature", classOf[Double]))
//
//        override def flatMap(value: SensorReading, out: Collector[(String, Double, Double)]): Unit = {
//          val lastTemperature = lateTempState.value()
//          val diff = (value.temperature - lastTemperature).abs
//          if (diff > 10){
//            out.collect((value.id, value.temperature, diff))
//          }
//          lateTempState.update(value.temperature)
//        }
//      })
      .flatMapWithState{
        case (data: SensorReading, None) => (List.empty, Some(data.temperature))
        case (data: SensorReading, lastTemp: Some[Double]) => {
          val diff = (data.temperature - lastTemp.get).abs
          if (diff > 10){
            (List((data.id, lastTemp.get, data.temperature)), Some(data.temperature))
          }
          else
            (List.empty, Some(data.temperature))
        }
      }


    env.execute("temperature change alert")

  }
}
