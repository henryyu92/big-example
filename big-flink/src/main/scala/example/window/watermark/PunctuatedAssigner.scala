package example.window.watermark

import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks
import org.apache.flink.streaming.api.watermark.Watermark

/**
  * @author Administrator
  * @date 2019/9/19
  */
class PunctuatedAssigner extends AssignerWithPunctuatedWatermarks[(String, Long, Int)] {
  // 定义 Watermark 生成逻辑
  override def checkAndGetNextWatermark(lastElement: (String, Long, Int), extractedTimestamp: Long): Watermark = {
    if (lastElement._3 == 0) new Watermark(extractedTimestamp) else null
  }

  // 定义 timestamp 抽取逻辑
  override def extractTimestamp(element: (String, Long, Int), previousElementTimestamp: Long): Long = {
    element._2
  }
}
