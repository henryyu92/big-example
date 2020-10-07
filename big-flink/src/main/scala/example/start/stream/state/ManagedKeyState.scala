//package example.start.stream.state
//
//import org.apache.flink.api.common.functions.RichFlatMapFunction
//import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
//import org.apache.flink.configuration.Configuration
//import org.apache.flink.streaming.api.scala._
//import org.apache.flink.util.Collector
//
//
//class ManagedKeyState {
//
//  def main(args: Array[String]): Unit = {
//    val env = StreamExecutionEnvironment.getExecutionEnvironment
//    val input = env.fromElements((2, 21L), (4, 1L), (5, 4L))
//    input.keyBy(_._1).flatMap(new RichFlatMapFunction[(Int, Long), (Int, Long, Long)] {
//      private var state: ValueState[Long] = _
//
//
//      override def open(parameters: Configuration): Unit = {
//        val stateDescriptor = new ValueStateDescriptor[Long]("valueState", classOf[Long])
//        state = getRuntimeContext.getState(stateDescriptor)
//      }
//
//      override def flatMap(value: (Int, Long), out: Collector[(Int, Long, Long)]): Unit = {
//        val stateValue = state.value()
//        if (value._2 > stateValue){
//          out.collect((value._1, value._2, stateValue))
//        }else{
//          state.update(value._2)
//          out.collect((value._1, value._2, value._2))
//        }
//      }
//    })
//  }
//
//}
