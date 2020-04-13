package example.window

import example.stream.SensorReading
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.util.Collector

object SideoutputStream {

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val processStream = env.socketTextStream("localhost", 7777)
      .map(data =>{
        val dataArray = data.split(",")
        SensorReading(dataArray(0).trim, dataArray(1).trim.toLong, dataArray(2).trim.toDouble)
      })
      .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[SensorReading](Time.minutes(1)) {
        override def extractTimestamp(element: SensorReading): Long = element.timestamp * 1000
      })
      .keyBy(_.id)
      .process(new FreezingAlert)

    processStream.print("process stream")
    processStream.getSideOutput(new OutputTag[String]("freezing alert")).print("side out stream")
  }
}

class FreezingAlert extends ProcessFunction[SensorReading, SensorReading]{

  lazy val alertOutput: OutputTag[String] = new OutputTag[String]("freezing alert")

  override def processElement(value: SensorReading, ctx: ProcessFunction[SensorReading, SensorReading]#Context, out: Collector[SensorReading]): Unit = {
    if(value.temperature < 32.0){
      ctx.output(alertOutput, "freezing alert for " + value.id)
    }else{
      out.collect(value)
    }
  }
}
