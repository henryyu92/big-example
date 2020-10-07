//package example.start.stream.transform
//
//import org.apache.flink.api.common.functions.MapFunction
//import org.apache.flink.streaming.api.scala._
//
//
//object StreamTransform {
//
//  def mapTransform[T, O](stream: DataStream[T], mapFunction: MapFunction[T, O]): DataStream[O] = {
//    stream.print("before map transform")
//    val mapStream = stream.map(mapFunction)
//    mapStream.print("after map transform")
//    mapStream
//  }
//}
