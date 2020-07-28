package example.stream.env

import org.apache.flink.api.scala.ExecutionEnvironment
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment


object EnvFactory {

  def main(args: Array[String]): Unit = {
    val env = EnvFactory.streamEnvBuilder.setParallelism(1).build
  }

  def streamEnvBuilder: StreamEnvBuilder = new StreamEnvBuilder

  def batchEnvBuilder: BatchEnvBuilder = new BatchEnvBuilder


  abstract class EnvBuilder[T] {
    def build: T
  }

  /**
    * build StreamExecutionEnvironment
    */
  protected class StreamEnvBuilder extends EnvBuilder[StreamExecutionEnvironment] {

    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment

    def setParallelism(parallelism: Int): StreamEnvBuilder = {
      env.setParallelism(parallelism)
      this
    }

    override def build: StreamExecutionEnvironment = env

  }

  protected class BatchEnvBuilder extends EnvBuilder[ExecutionEnvironment]{

    val env: ExecutionEnvironment = ExecutionEnvironment.getExecutionEnvironment

    override def build: ExecutionEnvironment = env
  }

}
