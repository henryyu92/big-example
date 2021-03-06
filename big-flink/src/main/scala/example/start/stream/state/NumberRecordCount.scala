package example.start.stream.state

import java.util
import java.util.Collections

import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.streaming.api.checkpoint.ListCheckpointed
import org.apache.flink.util.Collector

import scala.collection.JavaConverters.collectionAsScalaIterableConverter

/**
 * 统计输入到 FlatMapFunction 算子中的数据量
 */
class NumberRecordCount extends FlatMapFunction[(String, Long), (String, Long)] with ListCheckpointed[Long]{

  private var numberRecords: Long = 0L

  override def flatMap(value: (String, Long), out: Collector[(String, Long)]): Unit = {
    numberRecords += 1
    out.collect((value._1, numberRecords))
  }

  override def snapshotState(checkpointId: Long, timestamp: Long): util.List[Long] = {
    Collections.singletonList(numberRecords)
  }

  override def restoreState(state: util.List[Long]): Unit = {
    numberRecords = 0L
    for (count <- state.asScala){
      numberRecords += count
    }
  }
}
