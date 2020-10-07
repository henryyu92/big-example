package example.start.stream.source

import java.sql.Connection

import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.source.{RichSourceFunction, SourceFunction}


class FlinkJdbcSource[T](conn: Connection) extends RichSourceFunction[T]{


  override def open(parameters: Configuration): Unit = {

  }

  override def close(): Unit = {

  }

  override def run(ctx: SourceFunction.SourceContext[T]): Unit = {

  }

  override def cancel(): Unit = {

  }
}
