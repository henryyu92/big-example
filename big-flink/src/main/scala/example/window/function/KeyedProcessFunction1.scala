//package example.window.function
//
//import example.stream.SensorReading
//import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
//import org.apache.flink.streaming.api.functions.KeyedProcessFunction
//import org.apache.flink.streaming.api.scala._
//import org.apache.flink.util.Collector
//
//
//class KeyedProcessFunction1 extends KeyedProcessFunction[String, SensorReading, String] {
//
//  lazy val lastTemp: ValueState[Double] = getRuntimeContext.getState(new ValueStateDescriptor[Double]("last", classOf[Double]))
//  lazy val currentTimer: ValueState[Long] = getRuntimeContext.getState(new ValueStateDescriptor[Long]("currentTimer", classOf[Long]))
//
//  override def processElement(value: SensorReading, ctx: KeyedProcessFunction[String, SensorReading, String]#Context, out: Collector[String]): Unit = {
//    // 取出上次温度值
//    val preTemp = lastTemp.value()
//    lastTemp.update(value.temperature)
//
//    val curTimerTs = currentTimer.value()
//    // 判断温度连续上升
//    if (value.temperature > preTemp && curTimerTs == 0) {
//      val timerTs = ctx.timerService().currentProcessingTime() + 1000L
//      // 注册定时器
//      ctx.timerService().registerProcessingTimeTimer(timerTs)
//      currentTimer.update(timerTs)
//    } else if (preTemp > value.temperature || preTemp == 0.0) {
//      // 清除定时器
//      ctx.timerService().deleteProcessingTimeTimer(curTimerTs)
//      currentTimer.clear()
//    }
//  }
//
//
//  override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[String, SensorReading, String]#OnTimerContext, out: Collector[String]): Unit = {
//    // 输出报警信息
//    out.collect(ctx.getCurrentKey + "warning")
//    currentTimer.clear()
//  }
//
//  def keyedProcess[T, K, O](stream: KeyedStream[T, K], keyedProcessFunction: KeyedProcessFunction[K, T, O]): Unit = {
//    stream.process(keyedProcessFunction)
//  }
//}
