/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.test

import java.io.File
import java.util.{Set => JavaSet}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.implicitConversions

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hadoop.hive.ql.exec.FunctionRegistry
import org.apache.hadoop.hive.serde2.`lazy`.LazySimpleSerDe

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.CATALOG_IMPLEMENTATION
import org.apache.spark.sql.{SparkSession, SQLContext}
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry.FunctionBuilder
import org.apache.spark.sql.catalyst.expressions.ExpressionInfo
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.QueryExecution
import org.apache.spark.sql.execution.command.CacheTableCommand
import org.apache.spark.sql.hive._
import org.apache.spark.sql.hive.client.HiveClient
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.{ShutdownHookManager, Utils}

// SPARK-3729: Test key required to check for initialization errors with config.
object TestHive
  extends TestHiveContext(
    new SparkContext(
      System.getProperty("spark.sql.test.master", "local[1]"),
      "TestSQLContext",
      new SparkConf()
        .set("spark.sql.test", "")
        .set("spark.sql.hive.metastore.barrierPrefixes",
          "org.apache.spark.sql.hive.execution.PairSerDe")
        // SPARK-8910
        .set("spark.ui.enabled", "false")))


/**
 * A locally running test instance of Spark's Hive execution engine.
 *
 * Data from [[testTables]] will be automatically loaded whenever a query is run over those tables.
 * Calling [[reset]] will delete all tables and other state in the database, leaving the database
 * in a "clean" state.
 *
 * TestHive is singleton object version of this class because instantiating multiple copies of the
 * hive metastore seems to lead to weird non-deterministic failures.  Therefore, the execution of
 * test cases that rely on TestHive must be serialized.
 */
class TestHiveContext(
    @transient override val sparkSession: TestHiveSparkSession,
    isRootContext: Boolean)
  extends SQLContext(sparkSession, isRootContext) {

  def this(sc: SparkContext) {
    this(new TestHiveSparkSession(HiveUtils.withHiveExternalCatalog(sc)), true)
  }

  override def newSession(): TestHiveContext = {
    new TestHiveContext(sparkSession.newSession(), false)
  }

  override def sharedState: TestHiveSharedState = sparkSession.sharedState

  override def sessionState: TestHiveSessionState = sparkSession.sessionState

  def setCacheTables(c: Boolean): Unit = {
    sparkSession.setCacheTables(c)
  }

  def getHiveFile(path: String): File = {
    sparkSession.getHiveFile(path)
  }

  def loadTestTable(name: String): Unit = {
    sparkSession.loadTestTable(name)
  }

  def reset(): Unit = {
    sparkSession.reset()
  }

}


private[hive] class TestHiveSparkSession(
    @transient private val sc: SparkContext,
    val warehousePath: File,
    scratchDirPath: File,
    metastoreTemporaryConf: Map[String, String],
    @transient private val existingSharedState: Option[TestHiveSharedState])
  extends SparkSession(sc) with Logging { self =>

  // TODO: We need to set the temp warehouse path to sc's conf.
  // Right now, In SparkSession, we will set the warehouse path to the default one
  // instead of the temp one. Then, we override the setting in TestHiveSharedState
  // when we creating metadataHive. This flow is not easy to follow and can introduce
  // confusion when a developer is debugging an issue. We need to refactor this part
  // to just set the temp warehouse path in sc's conf.
  def this(sc: SparkContext) {
    this(
      sc,
      Utils.createTempDir(namePrefix = "warehouse"),
      TestHiveContext.makeScratchDir(),
      HiveUtils.newTemporaryConfiguration(useInMemoryDerby = false),
      None)
  }

  assume(sc.conf.get(CATALOG_IMPLEMENTATION) == "hive")

  // TODO: Let's remove TestHiveSharedState and TestHiveSessionState. Otherwise,
  // we are not really testing the reflection logic based on the setting of
  // CATALOG_IMPLEMENTATION.
  @transient
  override lazy val sharedState: TestHiveSharedState = {
    existingSharedState.getOrElse(
      new TestHiveSharedState(sc, warehousePath, scratchDirPath, metastoreTemporaryConf))
  }

  @transient
  override lazy val sessionState: TestHiveSessionState =
    new TestHiveSessionState(self, warehousePath)

  override def newSession(): TestHiveSparkSession = {
    new TestHiveSparkSession(
      sc, warehousePath, scratchDirPath, metastoreTemporaryConf, Some(sharedState))
  }

  private var cacheTables: Boolean = false

  def setCacheTables(c: Boolean): Unit = {
    cacheTables = c
  }

  // By clearing the port we force Spark to pick a new one.  This allows us to rerun tests
  // without restarting the JVM.
  System.clearProperty("spark.hostPort")

  // For some hive test case which contain ${system:test.tmp.dir}
  System.setProperty("test.tmp.dir", Utils.createTempDir().getCanonicalPath)

  /** The location of the compiled hive distribution */
  lazy val hiveHome = envVarToFile("HIVE_HOME")

  /** The location of the hive source code. */
  lazy val hiveDevHome = envVarToFile("HIVE_DEV_HOME")

  /**
   * Returns the value of specified environmental variable as a [[java.io.File]] after checking
   * to ensure it exists
   */
  private def envVarToFile(envVar: String): Option[File] = {
    Option(System.getenv(envVar)).map(new File(_))
  }

  val hiveFilesTemp = File.createTempFile("catalystHiveFiles", "")
  hiveFilesTemp.delete()
  hiveFilesTemp.mkdir()
  ShutdownHookManager.registerShutdownDeleteDir(hiveFilesTemp)

  val inRepoTests = if (System.getProperty("user.dir").endsWith("sql" + File.separator + "hive")) {
    new File("src" + File.separator + "test" + File.separator + "resources" + File.separator)
  } else {
    new File("sql" + File.separator + "hive" + File.separator + "src" + File.separator + "test" +
      File.separator + "resources")
  }

  def getHiveFile(path: String): File = {
    val stripped = path.replaceAll("""\.\.\/""", "").replace('/', File.separatorChar)
    hiveDevHome
      .map(new File(_, stripped))
      .filter(_.exists)
      .getOrElse(new File(inRepoTests, stripped))
  }

  val describedTable = "DESCRIBE (\\w+)".r

  case class TestTable(name: String, commands: (() => Unit)*)

  protected[hive] implicit class SqlCmd(sql: String) {
    def cmd: () => Unit = {
      () => new TestHiveQueryExecution(sql).hiveResultString(): Unit
    }
  }

  /**
   * A list of test tables and the DDL required to initialize them.  A test table is loaded on
   * demand when a query are run against it.
   */
  @transient
  lazy val testTables = new mutable.HashMap[String, TestTable]()

  def registerTestTable(testTable: TestTable): Unit = {
    testTables += (testTable.name -> testTable)
  }

  // The test tables that are defined in the Hive QTestUtil.
  // /itests/util/src/main/java/org/apache/hadoop/hive/ql/QTestUtil.java
  // https://github.com/apache/hive/blob/branch-0.13/data/scripts/q_test_init.sql
  @transient
  val hiveQTestUtilTables = Seq(
    TestTable("src",
      "CREATE TABLE src (key INT, value STRING)".cmd,
      s"LOAD DATA LOCAL INPATH '${getHiveFile("data/files/kv1.txt")}' INTO TABLE src".cmd),
    TestTable("src1",
      "CREATE TABLE src1 (key INT, value STRING)".cmd,
      s"LOAD DATA LOCAL INPATH '${getHiveFile("data/files/kv3.txt")}' INTO TABLE src1".cmd),
    TestTable("srcpart", () => {
      sql(
        "CREATE TABLE srcpart (key INT, value STRING) PARTITIONED BY (ds STRING, hr STRING)")
      for (ds <- Seq("2008-04-08", "2008-04-09"); hr <- Seq("11", "12")) {
        sql(
          s"""LOAD DATA LOCAL INPATH '${getHiveFile("data/files/kv1.txt")}'
             |OVERWRITE INTO TABLE srcpart PARTITION (ds='$ds',hr='$hr')
           """.stripMargin)
      }
    }),
    TestTable("srcpart1", () => {
      sql(
        "CREATE TABLE srcpart1 (key INT, value STRING) PARTITIONED BY (ds STRING, hr INT)")
      for (ds <- Seq("2008-04-08", "2008-04-09"); hr <- 11 to 12) {
        sql(
          s"""LOAD DATA LOCAL INPATH '${getHiveFile("data/files/kv1.txt")}'
             |OVERWRITE INTO TABLE srcpart1 PARTITION (ds='$ds',hr='$hr')
           """.stripMargin)
      }
    }),
    TestTable("src_thrift", () => {
      import org.apache.hadoop.hive.serde2.thrift.ThriftDeserializer
      import org.apache.hadoop.mapred.{SequenceFileInputFormat, SequenceFileOutputFormat}
      import org.apache.thrift.protocol.TBinaryProtocol

      sql(
        s"""
         |CREATE TABLE src_thrift(fake INT)
         |ROW FORMAT SERDE '${classOf[ThriftDeserializer].getName}'
         |WITH SERDEPROPERTIES(
         |  'serialization.class'='org.apache.spark.sql.hive.test.Complex',
         |  'serialization.format'='${classOf[TBinaryProtocol].getName}'
         |)
         |STORED AS
         |INPUTFORMAT '${classOf[SequenceFileInputFormat[_, _]].getName}'
         |OUTPUTFORMAT '${classOf[SequenceFileOutputFormat[_, _]].getName}'
        """.stripMargin)

      sql(
        s"LOAD DATA LOCAL INPATH '${getHiveFile("data/files/complex.seq")}' INTO TABLE src_thrift")
    }),
    TestTable("serdeins",
      s"""CREATE TABLE serdeins (key INT, value STRING)
         |ROW FORMAT SERDE '${classOf[LazySimpleSerDe].getCanonicalName}'
         |WITH SERDEPROPERTIES ('field.delim'='\\t')
       """.stripMargin.cmd,
      "INSERT OVERWRITE TABLE serdeins SELECT * FROM src".cmd),
    TestTable("episodes",
      s"""CREATE TABLE episodes (title STRING, air_date STRING, doctor INT)
         |STORED AS avro
         |TBLPROPERTIES (
         |  'avro.schema.literal'='{
         |    "type": "record",
         |    "name": "episodes",
         |    "namespace": "testing.hive.avro.serde",
         |    "fields": [
         |      {
         |          "name": "title",
         |          "type": "string",
         |          "doc": "episode title"
         |      },
         |      {
         |          "name": "air_date",
         |          "type": "string",
         |          "doc": "initial date"
         |      },
         |      {
         |          "name": "doctor",
         |          "type": "int",
         |          "doc": "main actor playing the Doctor in episode"
         |      }
         |    ]
         |  }'
         |)
       """.stripMargin.cmd,
      s"LOAD DATA LOCAL INPATH '${getHiveFile("data/files/episodes.avro")}' INTO TABLE episodes".cmd
    ),
    // THIS TABLE IS NOT THE SAME AS THE HIVE TEST TABLE episodes_partitioned AS DYNAMIC
    // PARTITIONING IS NOT YET SUPPORTED
    TestTable("episodes_part",
      s"""CREATE TABLE episodes_part (title STRING, air_date STRING, doctor INT)
         |PARTITIONED BY (doctor_pt INT)
         |STORED AS avro
         |TBLPROPERTIES (
         |  'avro.schema.literal'='{
         |    "type": "record",
         |    "name": "episodes",
         |    "namespace": "testing.hive.avro.serde",
         |    "fields": [
         |      {
         |          "name": "title",
         |          "type": "string",
         |          "doc": "episode title"
         |      },
         |      {
         |          "name": "air_date",
         |          "type": "string",
         |          "doc": "initial date"
         |      },
         |      {
         |          "name": "doctor",
         |          "type": "int",
         |          "doc": "main actor playing the Doctor in episode"
         |      }
         |    ]
         |  }'
         |)
       """.stripMargin.cmd,
      // WORKAROUND: Required to pass schema to SerDe for partitioned tables.
      // TODO: Pass this automatically from the table to partitions.
      s"""
         |ALTER TABLE episodes_part SET SERDEPROPERTIES (
         |  'avro.schema.literal'='{
         |    "type": "record",
         |    "name": "episodes",
         |    "namespace": "testing.hive.avro.serde",
         |    "fields": [
         |      {
         |          "name": "title",
         |          "type": "string",
         |          "doc": "episode title"
         |      },
         |      {
         |          "name": "air_date",
         |          "type": "string",
         |          "doc": "initial date"
         |      },
         |      {
         |          "name": "doctor",
         |          "type": "int",
         |          "doc": "main actor playing the Doctor in episode"
         |      }
         |    ]
         |  }'
         |)
        """.stripMargin.cmd,
      s"""
        INSERT OVERWRITE TABLE episodes_part PARTITION (doctor_pt=1)
        SELECT title, air_date, doctor FROM episodes
      """.cmd
      ),
    TestTable("src_json",
      s"""CREATE TABLE src_json (json STRING) STORED AS TEXTFILE
       """.stripMargin.cmd,
      s"LOAD DATA LOCAL INPATH '${getHiveFile("data/files/json.txt")}' INTO TABLE src_json".cmd)
  )

  hiveQTestUtilTables.foreach(registerTestTable)

  private val loadedTables = new collection.mutable.HashSet[String]

  def loadTestTable(name: String) {
    if (!(loadedTables contains name)) {
      // Marks the table as loaded first to prevent infinite mutually recursive table loading.
      loadedTables += name
      logDebug(s"Loading test table $name")
      val createCmds =
        testTables.get(name).map(_.commands).getOrElse(sys.error(s"Unknown test table $name"))
      createCmds.foreach(_())

      if (cacheTables) {
        new SQLContext(self).cacheTable(name)
      }
    }
  }

  /**
   * Records the UDFs present when the server starts, so we can delete ones that are created by
   * tests.
   */
  protected val originalUDFs: JavaSet[String] = FunctionRegistry.getFunctionNames

  /**
   * Resets the test instance by deleting any tables that have been created.
   * TODO: also clear out UDFs, views, etc.
   */
  def reset() {
    try {
      // HACK: Hive is too noisy by default.
      org.apache.log4j.LogManager.getCurrentLoggers.asScala.foreach { log =>
        val logger = log.asInstanceOf[org.apache.log4j.Logger]
        if (!logger.getName.contains("org.apache.spark")) {
          logger.setLevel(org.apache.log4j.Level.WARN)
        }
      }

      sharedState.cacheManager.clearCache()
      loadedTables.clear()
      sessionState.catalog.clearTempTables()
      sessionState.catalog.invalidateCache()

      sessionState.metadataHive.reset()

      FunctionRegistry.getFunctionNames.asScala.filterNot(originalUDFs.contains(_)).
        foreach { udfName => FunctionRegistry.unregisterTemporaryUDF(udfName) }

      // Some tests corrupt this value on purpose, which breaks the RESET call below.
      sessionState.conf.setConfString("fs.default.name", new File(".").toURI.toString)
      // It is important that we RESET first as broken hooks that might have been set could break
      // other sql exec here.
      sessionState.metadataHive.runSqlHive("RESET")
      // For some reason, RESET does not reset the following variables...
      // https://issues.apache.org/jira/browse/HIVE-9004
      sessionState.metadataHive.runSqlHive("set hive.table.parameters.default=")
      sessionState.metadataHive.runSqlHive("set datanucleus.cache.collections=true")
      sessionState.metadataHive.runSqlHive("set datanucleus.cache.collections.lazy=true")
      // Lots of tests fail if we do not change the partition whitelist from the default.
      sessionState.metadataHive.runSqlHive("set hive.metastore.partition.name.whitelist.pattern=.*")

      sessionState.catalog.setCurrentDatabase("default")
    } catch {
      case e: Exception =>
        logError("FATAL ERROR: Failed to reset TestDB state.", e)
    }
  }

}


private[hive] class TestHiveQueryExecution(
    sparkSession: TestHiveSparkSession,
    logicalPlan: LogicalPlan)
  extends QueryExecution(sparkSession, logicalPlan) with Logging {

  def this(sparkSession: TestHiveSparkSession, sql: String) {
    this(sparkSession, sparkSession.sessionState.sqlParser.parsePlan(sql))
  }

  def this(sql: String) {
    this(TestHive.sparkSession, sql)
  }

  override lazy val analyzed: LogicalPlan = {
    val describedTables = logical match {
      case CacheTableCommand(tbl, _, _) => tbl :: Nil
      case _ => Nil
    }

    // Make sure any test tables referenced are loaded.
    val referencedTables =
      describedTables ++
        logical.collect { case UnresolvedRelation(tableIdent, _) => tableIdent.table }
    val referencedTestTables = referencedTables.filter(sparkSession.testTables.contains)
    logDebug(s"Query references test tables: ${referencedTestTables.mkString(", ")}")
    referencedTestTables.foreach(sparkSession.loadTestTable)
    // Proceed with analysis.
    sparkSession.sessionState.analyzer.execute(logical)
  }
}


private[hive] class TestHiveFunctionRegistry extends SimpleFunctionRegistry {

  private val removedFunctions =
    collection.mutable.ArrayBuffer.empty[(String, (ExpressionInfo, FunctionBuilder))]

  def unregisterFunction(name: String): Unit = synchronized {
    functionBuilders.remove(name).foreach(f => removedFunctions += name -> f)
  }

  def restore(): Unit = synchronized {
    removedFunctions.foreach {
      case (name, (info, builder)) => registerFunction(name, info, builder)
    }
  }
}


private[hive] class TestHiveSharedState(
    sc: SparkContext,
    warehousePath: File,
    scratchDirPath: File,
    metastoreTemporaryConf: Map[String, String])
  extends HiveSharedState(sc) {

  override lazy val metadataHive: HiveClient = {
    TestHiveContext.newClientForMetadata(
      sc.conf, sc.hadoopConfiguration, warehousePath, scratchDirPath, metastoreTemporaryConf)
  }
}


private[hive] class TestHiveSessionState(
    sparkSession: TestHiveSparkSession,
    warehousePath: File)
  extends HiveSessionState(sparkSession) { self =>

  override lazy val conf: SQLConf = {
    new SQLConf {
      clear()
      override def caseSensitiveAnalysis: Boolean = getConf(SQLConf.CASE_SENSITIVE, false)
      override def clear(): Unit = {
        super.clear()
        TestHiveContext.overrideConfs.foreach { case (k, v) => setConfString(k, v) }
        setConfString("hive.metastore.warehouse.dir", self.warehousePath.toURI.toString)
      }
    }
  }

  override lazy val functionRegistry: TestHiveFunctionRegistry = {
    // We use TestHiveFunctionRegistry at here to track functions that have been explicitly
    // unregistered (through TestHiveFunctionRegistry.unregisterFunction method).
    val fr = new TestHiveFunctionRegistry
    org.apache.spark.sql.catalyst.analysis.FunctionRegistry.expressions.foreach {
      case (name, (info, builder)) => fr.registerFunction(name, info, builder)
    }
    fr
  }

  override def executePlan(plan: LogicalPlan): TestHiveQueryExecution = {
    new TestHiveQueryExecution(sparkSession, plan)
  }
}


private[hive] object TestHiveContext {

  /**
   * A map used to store all confs that need to be overridden in sql/hive unit tests.
   */
  val overrideConfs: Map[String, String] =
    Map(
      // Fewer shuffle partitions to speed up testing.
      SQLConf.SHUFFLE_PARTITIONS.key -> "5"
    )

  /**
   * Create a [[HiveClient]] used to retrieve metadata from the Hive MetaStore.
   */
  def newClientForMetadata(
      conf: SparkConf,
      hadoopConf: Configuration,
      warehousePath: File,
      scratchDirPath: File,
      metastoreTemporaryConf: Map[String, String]): HiveClient = {
    HiveUtils.newClientForMetadata(
      conf,
      hadoopConf,
      hiveClientConfigurations(hadoopConf, warehousePath, scratchDirPath, metastoreTemporaryConf))
  }

  /**
   * Configurations needed to create a [[HiveClient]].
   */
  def hiveClientConfigurations(
      hadoopConf: Configuration,
      warehousePath: File,
      scratchDirPath: File,
      metastoreTemporaryConf: Map[String, String]): Map[String, String] = {
    HiveUtils.hiveClientConfigurations(hadoopConf) ++ metastoreTemporaryConf ++ Map(
      // Override WAREHOUSE_PATH and METASTOREWAREHOUSE to use the given path.
      SQLConf.WAREHOUSE_PATH.key -> warehousePath.toURI.toString,
      ConfVars.METASTOREWAREHOUSE.varname -> warehousePath.toURI.toString,
      ConfVars.METASTORE_INTEGER_JDO_PUSHDOWN.varname -> "true",
      ConfVars.SCRATCHDIR.varname -> scratchDirPath.toURI.toString,
      ConfVars.METASTORE_CLIENT_CONNECT_RETRY_DELAY.varname -> "1")
  }

  def makeScratchDir(): File = {
    val scratchDir = Utils.createTempDir(namePrefix = "scratch")
    scratchDir.delete()
    scratchDir
  }

}
