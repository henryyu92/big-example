package example.start.stream.state

import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, ValueState, ValueStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext}
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.util.Collector

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

/**
 * 统计输入数据中每个 key 的数据元素数量和算子的元素数量
 */
class CheckpointCount extends FlatMapFunction[(Int, Long), (Int, Long, Long)] with CheckpointedFunction{

  // 定义算子实例本地变量，存储 Operator 数据数量
  private var operatorCount: Long = _
  // 定义 KeyedState，存储 key 相关的状态
  private var keyedState: ValueState[Long] = _
  // 定义 OperatorState，存储算子的状态
  private var operatorState: ListState[Long] = _

  override def flatMap(value: (Int, Long), out: Collector[(Int, Long, Long)]): Unit = {
    val keyedCount = keyedState.value() + 1
    keyedState.update(keyedCount)
    operatorCount = operatorCount + 1
    out.collect((value._1, keyedCount, operatorCount))
  }



  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    operatorState.clear()
    operatorState.add(operatorCount)
  }

  override def initializeState(context: FunctionInitializationContext): Unit = {

    keyedState = context.getKeyedStateStore.getState(new ValueStateDescriptor[Long]("keyedState", createTypeInformation[Long]))
    operatorState = context.getOperatorStateStore.getListState(new ListStateDescriptor[Long]("operatorState", createTypeInformation[Long]))
    if(context.isRestored){
      operatorCount = operatorState.get().asScala.sum
    }
  }

  def createTypeInformation[T]:TypeInformation[T] = TypeInformation.of(classOf[T])
}
