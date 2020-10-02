/*
 * Copyright 2015-2016 Snowflake Computing
 * Copyright 2015 Databricks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.snowflake.spark.snowflake

import java.sql.{Connection, Timestamp}
import java.time.{LocalDateTime, ZonedDateTime}
import java.util.TimeZone

import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.{
  JsonNode,
  ObjectMapper
}
import net.snowflake.spark.snowflake.Parameters.MergedParameters
import net.snowflake.spark.snowflake.Utils.SNOWFLAKE_SOURCE_NAME

import scala.util.Random
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql._
import org.apache.spark.sql.types.StructType
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.io.Source
import scala.collection.JavaConversions._
import scala.util.matching.Regex

/**
  * Base class for writing integration tests which run against a real Snowflake cluster.
  */
trait IntegrationSuiteBase
    extends IntegrationEnv
    with QueryTest {

  private val log = LoggerFactory.getLogger(getClass)

  def getAzureURL(input: String): String = {
    val azure_url = "wasbs?://([^@]+)@([^.]+)\\.([^/]+)/(.+)?".r
    input match {
      case azure_url(container, account, endpoint, _) =>
        s"fs.azure.sas.$container.$account.$endpoint"
      case _ => throw new IllegalArgumentException(s"invalid wasb url: $input")
    }
  }

  /**
    * Verify that the pushdown was done by looking at the generated SQL,
    * and check the results are as expected
    */
  def testPushdown(reference: String,
                   result: DataFrame,
                   expectedAnswer: Seq[Row],
                   bypass: Boolean = false,
                   printSqlText: Boolean = false): Unit = {

    // Verify the query issued is what we expect
    checkAnswer(result, expectedAnswer)

    // It is used to retrieve expected query text.
    if (printSqlText) {
      println(Utils.getLastSelect)
    }

    if (!bypass) {
      assert(
        Utils.getLastSelect.replaceAll("\\s+", "").toLowerCase == reference.trim
          .replaceAll("\\s+", "")
          .toLowerCase
      )
    }
  }

  /**
    * Save the given DataFrame to Snowflake, then load the results back into a DataFrame and check
    * that the returned DataFrame matches the one that we saved.
    *
    * @param tableName               the table name to use
    * @param df                      the DataFrame to save
    * @param expectedSchemaAfterLoad if specified, the expected schema after loading the data back
    *                                from Snowflake. This should be used in cases where you expect
    *                                the schema to differ due to reasons like case-sensitivity.
    * @param saveMode                the [[SaveMode]] to use when writing data back to Snowflake
    */
  def testRoundtripSaveAndLoad(
    tableName: String,
    df: DataFrame,
    expectedSchemaAfterLoad: Option[StructType] = None,
    saveMode: SaveMode = SaveMode.ErrorIfExists
  ): Unit = {
    try {
      df.write
        .format(SNOWFLAKE_SOURCE_NAME)
        .options(connectorOptions)
        .option("dbtable", tableName)
        .mode(saveMode)
        .save()
      assert(DefaultJDBCWrapper.tableExists(conn, tableName))
      val loadedDf = sparkSession.read
        .format(SNOWFLAKE_SOURCE_NAME)
        .options(connectorOptions)
        .option("dbtable", tableName)
        .load()
      assert(loadedDf.schema === expectedSchemaAfterLoad.getOrElse(df.schema))
      checkAnswer(loadedDf, df.collect())
    } finally {
      conn.createStatement.executeUpdate(s"drop table if exists $tableName")
      conn.commit()
    }
  }



  // Utility function to drop some garbage test tables.
  // Be careful to use this function which drops a bunch of tables.
  // Suggest you to run with "printOnly = true" to make sure the tables are correct.
  // And then run with "printOnly = false"
  // For example, dropTestTables(".*TEST_TABLE_.*\\d+".r, true)
  def dropTestTables(regex: Regex, printOnly: Boolean): Unit = {
    val statement = conn.createStatement()

    statement.execute("show tables")
    val resultset = statement.getResultSet
    while (resultset.next()) {
      val tableName = resultset.getString(2)
      tableName match {
        case regex() =>
          if (printOnly) {
            // scalastyle:off println
            println(s"will drop table: $tableName")
            // scalastyle:on println
          } else {
            jdbcUpdate(s"drop table $tableName")
          }
        case _ => None
      }
    }
  }
}
