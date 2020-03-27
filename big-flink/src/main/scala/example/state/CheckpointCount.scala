//package example.state
//
//import org.apache.flink.api.common.functions.FlatMapFunction
//import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, ValueState, ValueStateDescriptor}
//import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext}
//import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
//import org.apache.flink.util.Collector
//
//
//class CheckpointCount extends FlatMapFunction[(Int, Long), (Int, Long, Long)] with CheckpointedFunction{
//
//  private var operatorCount: Long = _
//  private var keyedState: ValueState[Long] = _
//  private var operatorState: ListState[Long] = _
//
//  override def flatMap(value: (Int, Long), out: Collector[(Int, Long, Long)]): Unit = {
//    val keyedCount = keyedState.value() + 1
//    keyedState.update(keyedCount)
//    operatorCount = operatorCount + 1
//    out.collect((value._1, keyedCount, operatorCount))
//  }
//
//  override def snapshotState(context: FunctionSnapshotContext): Unit = {
//    operatorState.clear()
//    operatorState.add(operatorCount)
//  }
//
//  override def initializeState(context: FunctionInitializationContext): Unit = {
//
//    keyedState = context.getKeyedStateStore.getState(new ValueStateDescriptor[Long]("keyedState", createTypeInformation[Long]))
//    operatorState = context.getOperatorStateStore.getListState(new ListStateDescriptor[Long]("operatorState", createTypeInformation[Long]))
//    if(context.isRestored){
//      operatorCount = operatorState.get().asScala.sum
//    }
//  }
//}
