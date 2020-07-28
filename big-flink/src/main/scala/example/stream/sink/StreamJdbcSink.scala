package example.stream.sink

import java.sql.{Connection, DriverManager, PreparedStatement}

import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.sink.{RichSinkFunction, SinkFunction}


class StreamJdbcSink[IN] extends RichSinkFunction[IN] {

  var conn: Connection = _
  var insertStmt: PreparedStatement = _
  var updateStmt: PreparedStatement = _


  override def open(parameters: Configuration): Unit = {
    conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/test", "root", "123456")
    insertStmt = conn.prepareStatement("INSERT INTO table_name(column) values(?)")
    updateStmt = conn.prepareStatement("UPDATE table_name SET column = ? WHERE column = ?")

  }
  override def invoke(value: IN, context: SinkFunction.Context[_]): Unit = {
  }

  override def close(): Unit = {

  }
}
