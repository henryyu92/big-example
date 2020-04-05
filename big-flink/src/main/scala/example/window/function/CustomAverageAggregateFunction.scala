package example.window.function

import org.apache.flink.api.common.functions.AggregateFunction


class CustomAverageAggregateFunction extends AggregateFunction[(String, Long),(Long, Long), Double]{
  // 初始化 (sum, count)
  override def createAccumulator(): (Long, Long) = (0L, 0L)
  // 定义 (sum, count) 的计算逻辑
  override def add(value: (String, Long), accumulator: (Long, Long)): (Long, Long) = {
    (accumulator._1 + value._2, accumulator._2 + 1)
  }
  // 最终结果
  override def getResult(accumulator: (Long, Long)): Double = accumulator._1 / accumulator._2
  // 合并逻辑
  override def merge(a: (Long, Long), b: (Long, Long)): (Long, Long) = (a._1 + b._1, a._2 + b._2)
}
