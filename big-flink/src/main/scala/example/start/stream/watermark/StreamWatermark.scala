package example.start.stream.watermark

import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.watermark.Watermark
import org.apache.flink.streaming.api.windowing.time.Time

object StreamWatermark {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // 设置全局时间语义
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val input = List(("a", 1L, 1), ("b", 1L, 1), ("b", 3L, 1))
    val source = env.addSource((ctx: SourceContext[(String, Long, Int)]) =>{
      input.foreach(value =>{
        // 增加 Event Time
        ctx.collectWithTimestamp(value, value._2)
        // 创建 Watermark，设定最大延时为 1
        ctx.emitWatermark(new Watermark(value._2 - 1))
      })
      ctx.emitWatermark(new Watermark(Long.MaxValue))
    })

    source.print
//    env.execute("Stream Watermark")


    val in = env.fromCollection(List(("a", 1L, 1), ("b", 1L, 1), ("b", 3L, 1),("a", 2L, 2)))
    in.assignAscendingTimestamps(t => t._3).keyBy(0).timeWindow(Time.seconds(10)).sum(2).print
    env.execute("Timed Window")

    in.assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[(String, Long, Int)](Time.seconds(10)) {
      override def extractTimestamp(element: (String, Long, Int)): Long = {
        element._2
      }
    })
  }

}
