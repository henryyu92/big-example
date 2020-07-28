package example

import kafka.admin.AdminUtils.{getRackAlternatedBrokerList, rand, replicaIndex}
import kafka.admin.BrokerMetadata

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
    val fixedStartIndex = 1
    val startPartitionId = 0

    val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
    var currentPartitionId = math.max(0, startPartitionId)
    var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)

    /**
      * 遍历分区
      *   startPartitionId        起始分配的分区 Id
      *   startIndex              分区分配的 broker 起始 Id
      *   nextReplicaShift        分区副本之间的间隔
      */
    for (_ <- 0 until nPartitions) {
      if (currentPartitionId > 0 && (currentPartitionId % brokerArray.length == 0))
        nextReplicaShift += 1
      // 计算分区起始 brokerId
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
    /**
      * 移动 1 - nBrokers
      */
    val shift = 1 + (secondReplicaShift + replicaIndex) % (nBrokers - 1)
    (firstReplicaIndex + shift) % nBrokers
  }


  def rackAssign(): Unit ={
    val brokerMetadatas = Seq(BrokerMetadata(1, Some("RACK1")))

    // 获取 broker-rack 信息
    val brokerRackMap = brokerMetadatas.collect { case BrokerMetadata(id, Some(rack)) =>
      id -> rack
    }.toMap
    val numRacks = brokerRackMap.values.toSet.size

    // 机架中的 broker 列表
    val arrangedBrokerList = new mutable.ArrayBuffer[Int](0)
    arrangedBrokerList.append(3, 6,1, 4, 7, 2, 5, 8)
    val numBrokers = arrangedBrokerList.size

    val ret = mutable.Map[Int, Seq[Int]]()

    // broker 起始位置
    val fixedStartIndex = 1
    // 起始 partition
    val startPartitionId = 0
    val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
    var currentPartitionId = math.max(0, startPartitionId)
    // 副本间隔
    var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)


    val nPartitions = 4
    val replicationFactor = 3

    /**
      * 遍历分区
      */
    for (_ <- 0 until nPartitions) {
      if (currentPartitionId > 0 && (currentPartitionId % arrangedBrokerList.size == 0))
        nextReplicaShift += 1
      // 计算分区第一个副本分配的 broker
      val firstReplicaIndex = (currentPartitionId + startIndex) % arrangedBrokerList.size
      // brokerId
      val leader = arrangedBrokerList(firstReplicaIndex)
      val replicaBuffer = mutable.ArrayBuffer(leader)
      // 已经分配的 机架
      val racksWithReplicas = mutable.Set(brokerRackMap(leader))
      // 已经分配的 broker
      val brokersWithReplicas = mutable.Set(leader)
      var k = 0
      for (_ <- 0 until replicationFactor - 1) {
        var done = false
        while (!done) {
          // 副本的 broker
          val broker = arrangedBrokerList(replicaIndex(firstReplicaIndex, nextReplicaShift * numRacks, k, arrangedBrokerList.size))
          // broker 对应的机架
          val rack = brokerRackMap(broker)
          // Skip this broker if
          // 1. there is already a broker in the same rack that has assigned a replica AND there is one or more racks
          //    that do not have any replica, or
          // 2. the broker has already assigned a replica AND there is one or more brokers that do not have replica assigned
          if ((!racksWithReplicas.contains(rack) || racksWithReplicas.size == numRacks)
            && (!brokersWithReplicas.contains(broker) || brokersWithReplicas.size == numBrokers)) {
            replicaBuffer += broker
            racksWithReplicas += rack
            brokersWithReplicas += broker
            done = true
          }
          k += 1
        }
      }
      ret.put(currentPartitionId, replicaBuffer)
      currentPartitionId += 1
    }
  }

}
