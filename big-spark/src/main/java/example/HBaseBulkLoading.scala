package example

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.{ConnectionFactory, Put}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.{KeyValue, TableName}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.{Partitioner, SparkContext}
import org.apache.spark.rdd.RDD

object HBaseBulkLoading {

  def bulkLoadWrite(rdd : RDD[Put], hbaseConf: Configuration, tableName: TableName): Unit = {
    val hbaseConnection = ConnectionFactory.createConnection(hbaseConf)
    val regionLocator = hbaseConnection.getRegionLocator(tableName)

  }

  def main(args: Array[String]): Unit = {

    val sc = new SparkContext("local", "test")
//    val config = new HBaseConfiguration();

    val rdd = sc.parallelize(Array(
      ("a", "foo1"),
      ("b", "foo1")
    ))

    rdd.sortBy(_._1).map(tuple =>{
      val kv = new KeyValue(Bytes.toBytes(tuple._1), Bytes.toBytes("cf"), Bytes.toBytes("cq"), Bytes.toBytes(tuple._2))
      (new ImmutableBytesWritable(Bytes.toBytes(tuple._1)), kv)
    })


  }

}

object HFilePartitionerHelper{
  object HFilePartitioner{
    def apply(conf: Configuration, split: Array[Array[Byte]], numberFilesPerRegionPerFamily: Int): HFilePartitioner = {

    }
  }

  protected abstract class HFilePartitioner extends Partitioner{
    def extractKey(n: Any): Array[Byte] = {
      n match {
        case kv:
      }
    }
  }
}