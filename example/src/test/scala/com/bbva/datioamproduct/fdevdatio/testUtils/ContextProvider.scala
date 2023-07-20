package com.bbva.datioamproduct.fdevdatio.testUtils

import com.datio.dataproc.sdk.datiosparksession.DatioSparkSession
import com.datio.dataproc.sdk.launcher.process.config.ProcessConfigLoader
import com.datio.dataproc.sdk.schema.DatioSchema
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.apache.spark.sql.{SQLContext, SparkSession}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, Suite}

import java.net.URI
import java.util.Locale

trait ContextProvider extends FlatSpec with BeforeAndAfterAll with Matchers {
  self: Suite =>

  @transient var datioSparkSession: DatioSparkSession = _

  //@transient var spark: SparkSession = _

  @transient var sparkContext: SparkContext = _

  @transient var sqlContext: SQLContext = _

  val config: Config = new ProcessConfigLoader().fromPath("src/test/resources/config/application-test.conf")

  private val sparkWarehouseDir: String = s"${System.getProperty("user.dir")}/src/test/resources/warehouse"

  override def beforeAll(): Unit = {
    super.beforeAll()

    Locale.setDefault(new Locale("CO"))

    SparkSession.builder()
      .master("local[*]")
      .config("spark.sql.warehouse.dir", sparkWarehouseDir)
      .config("spark.sql.catalogImplementation", "hive")
      .config("hive.exec.dynamic.partition", "true")
      .config("hive.exec.dynamic.partition.mode", "nonstrict")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("spark.hadoop.javax.jdo.option.ConnectionURL", "jdbc:derby:memory:db;create=true")
      .enableHiveSupport()
      .getOrCreate()

    datioSparkSession = DatioSparkSession.getOrCreate()

    sparkContext = datioSparkSession.getSparkSession.sparkContext

    sqlContext = datioSparkSession.getSparkSession.sqlContext

    createDataCatalog()
  }

  override def afterAll(): Unit = {
    super.afterAll()

    if (datioSparkSession.getSparkSession != null) {
      datioSparkSession.getSparkSession.stop()
    }
  }

  private def createDataCatalog(): Unit = {
    datioSparkSession.getSparkSession.sql("CREATE DATABASE IF NOT EXISTS input")
    datioSparkSession.getSparkSession.sql("CREATE DATABASE IF NOT EXISTS output")
    createTable(
      s"$sparkWarehouseDir/schema/t_fdev_customers.output.schema",
      "t_fdev_customers",
      partitions = List("gl_date")
    )
    createTable(
      s"$sparkWarehouseDir/schema/t_fdev_phones.output.schema",
      "t_fdev_phones",
      partitions = List("cutoff_date")
    )
    createTable(
      s"$sparkWarehouseDir/schema/t_fdev_customersphones.output.schema",
      "t_fdev_customersphones",
      partitions = List("jwk_date"),
      db = "output"
    )
  }

  private def createTable(schemaPath: String, tableName: String, partitions: List[String] = Nil, db: String = "input"): Unit = {

    val datioSchema = DatioSchema.getBuilder
      .fromURI(new URI(schemaPath))
      .withMetadataFields(true)
      .withDeletedFields(true)
      .build()

    val fieldsDDL: String = datioSchema.getStructType
      .map(_.toDDL).mkString(",")

    val createTableDLL: String = s"CREATE EXTERNAL TABLE IF NOT EXISTS $db.$tableName " +
      s"($fieldsDDL) " +
      s"STORED AS parquet " +
      s"LOCATION '$tableName'"

    partitions match {
      case Nil => datioSparkSession.getSparkSession.sql(createTableDLL)
      case _ =>
        datioSparkSession.getSparkSession.sql(s"$createTableDLL PARTITIONED BY (${partitions.mkString(",")})")
        datioSparkSession.getSparkSession.sql(s"MSCK REPAIR TABLE $db.$tableName")
    }

  }

}