package example.start.table.api

import example.start.env.EnvFactory
import org.apache.flink.table.api.EnvironmentSettings
import org.apache.flink.table.api.scala.StreamTableEnvironment


class TableSource {

  def regCataLog(): Unit ={
    val env = EnvFactory.streamEnvBuilder.build

    val fsSettings = EnvironmentSettings.newInstance().useOldPlanner().inStreamingMode().build()

    StreamTableEnvironment.create(env, fsSettings)
  }
}
