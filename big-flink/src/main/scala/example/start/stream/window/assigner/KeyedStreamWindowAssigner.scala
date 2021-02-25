package example.start.stream.window.assigner

import example.start.stream.SensorReading
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.assigners.{SlidingEventTimeWindows, TumblingEventTimeWindows, TumblingProcessingTimeWindows}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.{GlobalWindow, TimeWindow}

/**
 * KeyedStream 提供的窗口分配器
 *  - timeWindow()   根据时间语义分配窗口，默认是 ProcessTime
 *  - countWindow()
 *  - sessionWindow()
 *  - window()
 */
class KeyedStreamWindowAssigner {

  def timeWindow[T, K](keyedStream: KeyedStream[T, K]): WindowedStream[T, K, TimeWindow] = {
    keyedStream
//      .timeWindow(Time.seconds(15))   // 分配滚动时间窗口，时间语义由 ExecutionEnvironment 确定
//      .timeWindow(Time.seconds(15), Time.seconds(5))  // 分配滑动时间窗口，时间语义由 ExecutionEnvironment 确定
//      .window(TumblingEventTimeWindows.of(Time.seconds(15)))    // 分配 EventTime 时间语义的滚动窗口
//      .window(TumblingProcessingTimeWindows.of(Time.seconds(15))) // 分配 ProcessTime 时间语义的滚动窗口
      .window(SlidingEventTimeWindows.of(Time.seconds(15), Time.seconds(5)))
  }

  def countWindow[T, K](keyedStream: KeyedStream[T, K]): WindowedStream[T, K, GlobalWindow] = {
    // countWindow 底层由 GlobalWindow 实现，自定义时需要指定触发器
    keyedStream.countWindow(10)
  }

}


object KeyedStreamWindowAssigner{

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val stream = env.socketTextStream("host", 80)
      .map(data => {
        val arr = data.split(",")
        SensorReading(arr(0), arr(1).toLong, arr(2).toDouble)
      })

    val windowedStream = stream.map(data => (data.id, data.temperature))
      .keyBy(_._1)
      .timeWindow(Time.hours(1))
  }
}