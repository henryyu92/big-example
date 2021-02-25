//package example.start.stream.api.transform
//
//import org.apache.flink.streaming.api.scala._
//
//
//object MultiStreamTransform {
//
//
//  def connectStream[T1, T2](source: DataStream[T1], extra: DataStream[T2]):ConnectedStreams[T1, T2]={
//    source.connect(extra)
//  }
//
//  def unionStream[T](streams: DataStream[T]*):DataStream[T]={
//    streams.reduce((s1, s2)=> s1.union(s2))
//  }
//
//  def mapTransform[IN1, IN2, R](stream: ConnectedStreams[IN1, IN2], fun1: IN1=>R, func2: IN2=>R): DataStream[R]={
//    stream.map(fun1, func2)
//  }
//
//
//
//
//}
