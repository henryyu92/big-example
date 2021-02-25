package example.start.stream.process

import example.start.stream.SensorReading
import org.apache.flink.api.common.state.ValueStateDescriptor
import org.apache.flink.api.scala.createTypeInformation
import org.apache.flink.streaming.api.functions.{KeyedProcessFunction, ProcessFunction}
import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.util.Collector

object ProcessFunctionDemo {

}

/**
 * 温度持续上升则发出警报
 */
class TempIncreaseWarning(interval: Long) extends KeyedProcessFunction[String, SensorReading, String]{

  lazy val lastTempState = getRuntimeContext.getState(new ValueStateDescriptor[Double]("last temperature", classOf[Double]))
  lazy val timerTsState = getRuntimeContext.getState(new ValueStateDescriptor[Long]("last timer", classOf[Long]))

  override def processElement(value: SensorReading, ctx: KeyedProcessFunction[String, SensorReading, String]#Context, out: Collector[String]): Unit = {
    val lastTemperature = lastTempState.value()
    val timerTs = timerTsState.value()
    if (value.temperature > lastTemperature && timerTs > 0){
      // 注册定时器，在定时器到达时触发 onTimer 计算
      val ts = ctx.timerService().currentProcessingTime() + 1
      ctx.timerService().registerProcessingTimeTimer(ts)
      timerTsState.update(ts)
      lastTempState.update(value.temperature)
    }else if (value.temperature < lastTemperature){
      ctx.timerService().deleteProcessingTimeTimer(timerTs)
      timerTsState.clear()
    }
  }

  /**
   * 定时器触发
   */
  override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[String, SensorReading, String]#OnTimerContext, out: Collector[String]): Unit = {
    out.collect(timestamp + "" + ctx.getCurrentKey)
  }
}

/**
 * 侧输出流分流
 */
class SplitTempProcessor(threshold: Long) extends ProcessFunction[SensorReading, SensorReading]{
  override def processElement(value: SensorReading, ctx: ProcessFunction[SensorReading, SensorReading]#Context, out: Collector[SensorReading]): Unit = {

    if (value.temperature >= threshold){
      out.collect(value)
    }else{
      ctx.output(OutputTag[(String, Long, Double)]("low"), (value.id, value.temperature))
    }


  }
}