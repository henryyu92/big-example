package example.start.dataset

import example.start.env.EnvFactory
import org.apache.flink.api.scala._


object WordCount {

  def main(args: Array[String]): Unit = {

    // 创建执行环境
    val env = EnvFactory.batchEnvBuilder.build

    // source
    val input = WordCount.getClass.getClassLoader.getResource("data.txt").getFile

    // transform
    val data = env.readTextFile(input)
      .flatMap(_.split(" "))
      .map((_, 1))
      .groupBy(0)
      .sum(1)

    // sink
    data.print()

  }

}
