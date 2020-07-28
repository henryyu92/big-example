package example.window.watermark

import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks
import org.apache.flink.streaming.api.watermark.Watermark

/**
  * 周期性生成 Watermark
  */
class PeriodicAssigner extends AssignerWithPeriodicWatermarks[(String, Long, Int)]{
  val maxOutOfOrder = 1000L
  var currentMaxTimestamp : Long = Long.MinValue
  // 定义生成 watermark 逻辑
  override def getCurrentWatermark: Watermark = {
    new Watermark(currentMaxTimestamp - maxOutOfOrder)
  }
  // 定义抽取 timestamp 逻辑
  override def extractTimestamp(element: (String, Long, Int), previousElementTimestamp: Long): Long = {
    val currentTimestamp = element._2
    currentMaxTimestamp = Math.max(currentTimestamp, currentMaxTimestamp)
    currentTimestamp
  }
}
