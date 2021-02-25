package example.start.schema

import org.apache.flink.api.common.serialization.DeserializationSchema
import org.apache.flink.api.common.typeinfo.TypeInformation

/**
 * 自定义数据类型转换
 */
//class SourceEventSchema extends DeserializationSchema[SourceEvent]{
//  override def deserialize(message: Array[Byte]): SourceEvent = SourceEvent.
//
//  override def isEndOfStream(nextElement: SourceEvent): Boolean = false
//
//  override def getProducedType: TypeInformation[SourceEvent] = TypeInformation.of(SourceEvent.getClass)
//}
//
//class SourceEvent(str){
//
//  def fromString(str: String): SourceEvent ={
//    SourceEvent(str)
//  }
//}