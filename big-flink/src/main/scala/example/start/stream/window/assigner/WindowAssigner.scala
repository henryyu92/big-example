package example.start.stream.window.assigner

import org.apache.flink.streaming.api.scala.{AllWindowedStream, KeyedStream, WindowedStream}
import org.apache.flink.streaming.api.windowing.assigners.{SlidingEventTimeWindows, SlidingProcessingTimeWindows}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow

/**
 * 窗口分配是对数据集的划分，Flink 使用 WindowAssigner 接口定义窗口的分配
 *
 *  - TumblingEventTimeWindow
 *  - SlidingEventTimeWindow
 */
object WindowAssigner {

  /**
   * 基于 DataStream 的窗口分配
   */
  def dataStreamTimeWindowAssigner(): Unit ={

  }

  /**
   * 基于 KeyedStream 的窗口分配
   */
  def keyedStreamTimeWindowAssigner(): Unit ={

  }

  def eventTimeSlidingWindow(stream: KeyedStream[String, String]): WindowedStream[String, String, TimeWindow] = {
    stream.window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(10)))
  }

  def processingTimeSlidingWindow(stream: KeyedStream[String, String]): WindowedStream[String, String, TimeWindow] = {
    stream.window(SlidingProcessingTimeWindows.of(Time.hours(1), Time.minutes(10)))
  }

}
