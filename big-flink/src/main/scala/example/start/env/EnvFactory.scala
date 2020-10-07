package example.start.env


object EnvFactory {

  def main(args: Array[String]): Unit = {
    val env = EnvFactory.streamEnvBuilder.setParallelism(1).build
  }

  def streamEnvBuilder: StreamEnvBuilder = new StreamEnvBuilder

  def batchEnvBuilder: BatchEnvBuilder = new BatchEnvBuilder

}
