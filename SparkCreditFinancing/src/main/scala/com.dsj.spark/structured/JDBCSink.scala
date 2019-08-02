package com.dsj.spark.structedStreaming

import java.sql._
import org.apache.spark.sql.ForeachWriter
import org.apache.spark.sql.Row

/**
  * 自定义JDBC Sink
  */
class JDBCSink(url: String, username: String, password: String) extends ForeachWriter[Row] {

  var statement: Statement = _
  var resultSet: ResultSet = _
  var connection: Connection = _

  override def open(partitionId: Long, version: Long): Boolean = {
    // open connection
    Class.forName(Constants.driver)
    //connection = DriverManager.getConnection(url, username, password)
    connection = new MySqlPool(url, username, password).getJdbcConn()
    statement = connection.createStatement()
    return true
  }

  override def process(record: Row) = {
    // write string to connection
    val appname = record.getAs[String]("appname")
    val count = record.getAs[Long]("count").asInstanceOf[Int]
    val sql = "select 1 from appCounts where appname = '" + appname + "'"

    val insertSql = "insert into appCounts(appname,count) values('" + appname + "'," + count + ")"

    val updateSql = "update appCounts set count = " + count + " where appname = '" + appname + "'"

    try {
      resultSet = statement.executeQuery(sql)

      if (resultSet.next()) {
        statement.executeUpdate(updateSql)
      } else {
        statement.execute(insertSql)
      }
    } catch {
      case ex: SQLException => {
        println("SQLException")
      }

      case ex: Exception => {

        println("Exception")
      }

      case ex: RuntimeException => {
        println("RuntimeException")
      }

      case ex: Throwable => {
        println("Throwable")
      }

    }
  }

  override def close(errorOrNull: Throwable): Unit = {
    // close the connection
    if (statement != null) {
      statement.close()
    }

    if (connection != null) {
      connection.close()

    }
  }

}