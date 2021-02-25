package example.start.stream.window.assigner

import org.apache.flink.streaming.api.scala.{KeyedStream, StreamExecutionEnvironment, WindowedStream}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow

/**
 * 基于时间的窗口分配器
 */
object TimeWindow {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment

  }

  def tumblingTimeWindow[T, K](stream: KeyedStream[T, K], slide: Time): WindowedStream[T, K, TimeWindow] = {
    stream.timeWindow(slide)
  }

  def slidingTimeWindow[T, K](stream: KeyedStream[T, K], size: Time, slide: Time): WindowedStream[T, K, TimeWindow] = {
    stream.timeWindow(size, slide)
  }

  def sessionWindow(): Unit ={

  }
}
