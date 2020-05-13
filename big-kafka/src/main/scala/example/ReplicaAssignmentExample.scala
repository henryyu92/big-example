package example

import kafka.admin.AdminUtils.{rand}

import collection.{Map, mutable, _}

object ReplicaAssignmentExample {

  def main(args: Array[String]): Unit = {
    assign()
  }

  def assign(): Unit ={
    val ret = mutable.Map[Int, Seq[Int]]()
    val nPartitions = 4
    val replicationFactor = 3
    val brokerArray = Array(0, 1, 2, 3, 4)
    val fixedStartIndex = 0
    val startPartitionId = 0

    val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
    var currentPartitionId = math.max(0, startPartitionId)
    var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)

    /**
      * 遍历分区
      */
    for (_ <- 0 until nPartitions) {
      if (currentPartitionId > 0 && (currentPartitionId % brokerArray.length == 0))
        nextReplicaShift += 1
      // 计算起始 brokerId
      val firstReplicaIndex = (currentPartitionId + startIndex) % brokerArray.length
      val replicaBuffer = mutable.ArrayBuffer(brokerArray(firstReplicaIndex))

      /**
        * 遍历副本
        */
      for (j <- 0 until replicationFactor - 1)
        replicaBuffer += brokerArray(replicaIndex(firstReplicaIndex, nextReplicaShift, j, brokerArray.length))
      ret.put(currentPartitionId, replicaBuffer)
      currentPartitionId += 1
    }
    print(ret)
  }

  private def replicaIndex(firstReplicaIndex: Int, secondReplicaShift: Int, replicaIndex: Int, nBrokers: Int): Int = {
    val shift = 1 + (secondReplicaShift + replicaIndex) % (nBrokers - 1)
    (firstReplicaIndex + shift) % nBrokers
  }

}
