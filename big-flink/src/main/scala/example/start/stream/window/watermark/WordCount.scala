package example.start.stream.window.watermark

import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.{_}

/**
  * @author Administrator
  * @date 2019/8/2
  */
object WordCount {

  def main(args: Array[String]): Unit = {
    val params = ParameterTool.fromArgs(args)
    val input = if(params.has("input")) params.get("input") else "input.txt"

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val text = env.readTextFile(input)
    val counts = text
      .flatMap(_.toLowerCase.split(" "))
      .filter(_.nonEmpty).map((_, 1))
      .keyBy(0)
      .sum(1)

    if (params.has("output")) {
      counts.writeAsText(params.get("output"))
    } else {
      counts.print
    }
    env.execute("Streaming WordCount")
  }

}
