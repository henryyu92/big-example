package example.start.stream.api.transform

import org.apache.flink.api.common.functions.{RichFlatMapFunction, RichMapFunction}
import org.apache.flink.util.Collector

object SingleStreamTransform {

}


/**
 * MapFunction 作用于每一个元素，将元素从一个类型转换为另一个类型，返回作用后的元素集合
 */
class PaddingMapFunction[IN] extends RichMapFunction {

  // 填充缺失值
  override def map(value: IN): IN = {
    value
  }
}

/**
 *
 */
class SplitFlatMapFunction[String, Int] extends RichFlatMapFunction {
  override def flatMap(value: Nothing, out: Collector[Nothing]): Unit = {

  }
}