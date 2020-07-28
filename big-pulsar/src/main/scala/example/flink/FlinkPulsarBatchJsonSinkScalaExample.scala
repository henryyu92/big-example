package example.flink

import org.apache.pulsar.client.impl.auth.AuthenticationDisabled

/**
  * Implements a batch Scala program on Pulsar topic by writing Flink DataSet as Json.
  */
object FlinkPulsarBatchJsonSinkScalaExample {

  /**
    * NasaMission Model
    */
  private case class NasaMission(@BeanProperty id: Int,
                         @BeanProperty missionName: String,
                         @BeanProperty startYear: Int,
                         @BeanProperty endYear: Int)

  private val nasaMissions = List(
    NasaMission(1, "Mercury program", 1959, 1963),
    NasaMission(2, "Apollo program", 1961, 1972),
    NasaMission(3, "Gemini program", 1963, 1966),
    NasaMission(4, "Skylab", 1973, 1974),
    NasaMission(5, "Apollo–Soyuz Test Project", 1975, 1975))

  def main(args: Array[String]): Unit = {

    // parse input arguments
    val parameterTool = ParameterTool.fromArgs(args)

    if (parameterTool.getNumberOfParameters < 2) {
      println("Missing parameters!")
      println("Usage: pulsar --service-url <pulsar-service-url> --topic <topic>")
      return
    }

    // set up the execution environment
    val env = ExecutionEnvironment.getExecutionEnvironment
    env.getConfig.setGlobalJobParameters(parameterTool)

    val serviceUrl = parameterTool.getRequired("service-url")
    val topic = parameterTool.getRequired("topic")

    println("Parameters:")
    println("\tServiceUrl:\t" + serviceUrl)
    println("\tTopic:\t" + topic)

    // create PulsarJsonOutputFormat instance
    val pulsarJsonOutputFormat = new PulsarJsonOutputFormat[NasaMission](serviceUrl, topic, new AuthenticationDisabled())

    // create DataSet
    val nasaMissionDS = env.fromCollection(nasaMissions)

    // map nasa mission names to upper-case
    nasaMissionDS.map(nasaMission =>
      NasaMission(
        nasaMission.id,
        nasaMission.missionName.toUpperCase,
        nasaMission.startYear,
        nasaMission.endYear))

    // filter missions which started after 1970
    .filter(_.startYear > 1970)

    // write batch data to Pulsar
    .output(pulsarJsonOutputFormat)

    // set parallelism to write Pulsar in parallel (optional)
    env.setParallelism(2)

    // execute program
    env.execute("Flink - Pulsar Batch Json")
  }

}
