package example.start.stream.window.assigner

import org.apache.flink.streaming.api.scala.{KeyedStream, WindowedStream}
import org.apache.flink.streaming.api.windowing.assigners.{SlidingEventTimeWindows, SlidingProcessingTimeWindows}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow

object WindowAssigner {

  def eventTimeSlidingWindow(stream: KeyedStream[String, String]): WindowedStream[String, String, TimeWindow] = {
    stream.window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(10)))
  }

  def processingTimeSlidingWindow(stream: KeyedStream[String, String]): WindowedStream[String, String, TimeWindow] = {
    stream.window(SlidingProcessingTimeWindows.of(Time.hours(1), Time.minutes(10)))
  }

}
