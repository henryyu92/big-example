package example.dataset

import example.stream.env.EnvFactory
import org.apache.flink.api.scala._


object WordCount {

  def main(args: Array[String]): Unit = {

    val env = EnvFactory.batchEnvBuilder.build

    // source
    val input = WordCount.getClass.getClassLoader.getResource("data.txt").getFile

    env.readTextFile(input)
      .flatMap(_.split(" "))
      .map((_, 1))
      .groupBy(0)
      .sum(1)
      .print()

  }

}
