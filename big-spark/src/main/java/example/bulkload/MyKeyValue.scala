package example.bulkload

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}

import org.apache.hadoop.hbase.KeyValue

class MyKeyValue(var row: Array[Byte], var family: Array[Byte],
                 var qualifier: Array[Byte], var timestamp: Long,
                 var value: Array[Byte])
  extends Serializable  with Ordered[MyKeyValue] {

  var keyValue: KeyValue = _

  def getKeyValue(): KeyValue = {
    if (keyValue == null){
      keyValue = new KeyValue(row, family, qualifier, timestamp, value)
    }
    keyValue
  }

  @throws[IOException]
  private def writeObject(out: ObjectOutputStream): Unit ={
    keyValue = null
    out.defaultWriteObject()
    out.writeObject(this)
  }

  private def readObject(in: ObjectInputStream): Unit ={
    in.defaultReadObject()
    val newKeyValue = in.readObject().asInstanceOf[MyKeyValue]
    this.row = newKeyValue.row
    this.family = newKeyValue.family
    this.qualifier = newKeyValue.qualifier
    this.timestamp = newKeyValue.timestamp
    this.value = newKeyValue.value
    getKeyValue()
  }

  class MyComparator extends KeyValue.KVComparator with Serializable{}
  val comparator = new MyComparator()

  override def compare(that: MyKeyValue): Int = {
    comparator.compare(this.getKeyValue(), that.getKeyValue())
  }

  override def toString: String = {
    getKeyValue().toString
  }
}
