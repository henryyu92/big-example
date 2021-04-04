package example.bulkload

import org.apache.hadoop.conf.Configuration

/**
 * https://my.oschina.net/u/4405433/blog/3653060
 */
object HFilePartitionerHelper {

  object HFilePartitioner {
    def apply(conf: Configuration, split: Array[Array[Byte]], numberFilesPerRegionPerFamily: Int): HFilePartitioner = {
      if (numberFilesPerRegionPerFamily == 1){
        new SingleFilePartitioner[split]
      }else{
        val fraction = 1 max numberFilesPerRegionPerFamily min conf.getInt(LoadIncrementalHFiles.MAX_FILES_PER_REGION_PER_FAMILY)
      }
    }
  }

}
