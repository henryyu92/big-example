//package example.stream.transform
//
//import org.apache.flink.api.common.functions.ReduceFunction
//import org.apache.flink.streaming.api.datastream.{DataStream, KeyedStream}
//
//
//object KeyedStreamTransform {
//
//
//
//
//  def reduceTransform[T](stream: KeyedStream[T], reduceFunction: ReduceFunction[T]):DataStream[T] ={
//
//    stream.reduce(reduceFunction)
//  }
//
//}
