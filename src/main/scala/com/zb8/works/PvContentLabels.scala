package com.zb8.works

import java.util
import java.util.{Calendar, Date}

import com.zb8.utils.{PhoenixJDBCUtil, PhoenixDataSourceUtils, TimeUtils}
import org.apache.phoenix.spark._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
  * @ Author:
  * @ Description:
  * @ Date:  Created in 14:09 2018/9/22
  * @ Modified  By:
  */
object PvContentLabels {

  //vpc网络
  //      val zkAddress = "hb-xxxxx-002.hbase.rds.aliyuncs.com,hb-xxxxx-001.hbase.rds.aliyuncs.com,hb-xxxxx-003.hbase.rds.aliyuncs.com:2181"
  //经典网络
  val zkAddress = "hb-proxy-pub-xxxxx-002.hbase.rds.aliyuncs.com,hb-proxy-pub-xxxxx-001.hbase.rds.aliyuncs.com,hb-proxy-pub-xxxxx-003.hbase.rds.aliyuncs.com:2181"
  val phoenixJdbcUrl = "jdbc:phoenix:" + zkAddress
  PhoenixJDBCUtil.setPhoenixJDBCUrl("jdbc:phoenix:" + zkAddress)

  def main(args: Array[String]): Unit = {
    //判断入参正确性
    if (args.length != 1) {
      println("执行main方法时，入参个数错误。")
      System.exit(1)
    }
    val conf = new SparkConf().setAppName("PvContentLabels")
      .setMaster("local[8]")
    val ss = SparkSession.builder().config(conf).getOrCreate()
    var phoenixDF: DataFrame = null
    try {
      //参数：批量计算的数据时间跨度（单位：小时）
      val timeStep: Int = args(0).toInt
      //获取数据集的起始/终止时间
      val newTimes: util.List[String] = getNewTime(timeStep, ss)
      val newStartTime = newTimes.get(0)
      val newEndTime = newTimes.get(1)
      //判断是否执行计算：如果hbase已存在下个批次的数据则可认为当前时间段数据已完全入库，可进行计算任务
      val value: Any = isHasNextDurData(ss, newEndTime)
      if (value == null) {
        println("当前计算时段的数据尚未入库完成，暂不执行计算。")
        System.exit(0)
      }
      //映射spark-phoenix表
      phoenixDF = loadPHoenixDataAsDFByDataSource(ss, "ZB8_CLICKLOG", zkAddress)
      phoenixDF.createOrReplaceTempView("ZB8_CLICKLOG")
      val sql =
        s"""
           |select CONTENT_LABEL from ZB8_CLICKLOG where time>='${newStartTime}' and time<'${newEndTime}' and CONTENT_LABEL is not null
         """.stripMargin
      val phoenixDFInTime = ss.sql(sql) //.persist(StorageLevel.MEMORY_AND_DISK_SER)
      phoenixDFInTime.rdd.map(row => {
        val CONTENT_LABEL: String = row.getAs[String]("CONTENT_LABEL")
        val TIME: String = row.getAs[String]("TIME")
        (TIME, CONTENT_LABEL)
      })
      val labelWCRDD: RDD[(String, String, String, Int)] = phoenixDFInTime.rdd
        .flatMap(row => {
          val CONTENT_LABEL: String = row.getString(0)
          CONTENT_LABEL.split(",")
        })
        .map((_, 1))
        .reduceByKey(_ + _)
        .map(line => {
          val word = line._1
          val count = line._2
          (newEndTime, newStartTime, word, count)
        })

      //将结果存入hbase
      labelWCRDD.saveToPhoenix(
        tableName = "ZB8_STAT_LABEL",
        cols = Seq("ENDTIME", "STARTTIME", "WORD", "FREQUENCY"), //列名严格区分大小写，程序不会转换成大写
        zkUrl = Some(phoenixJdbcUrl)
      )
      //存入最近计算时间
      val sss = s"upsert into ZB8_STAT_LABEL(ENDTIME,STARTTIME) values('${newEndTime}','${newStartTime}')"
      PhoenixJDBCUtil.insert(sss, Array[AnyRef]())
    } catch {
      case e: Exception => {
        println(e.getMessage, e)
        System.exit(1)
      }
    } finally {
      //if (phoenixDF != null) phoenixDF.unpersist()
      ss.stop()
    }
  }

  /**
    * 1.使用Data Source API加载Phoenix数据成 DF（处理17.5个小时的数据用时20分钟，spark执行计划中task任务较多(160个),hbase的cpu占用不高）
    * （暂时推荐使用，优于其它两种方式）
    */
  def loadPHoenixDataAsDFByDataSource(ss: SparkSession, tableName: String, zkAddress: String): DataFrame = {
    val phoenixDF: DataFrame = ss.read
      .options(Map("table" -> tableName, "zkUrl" -> zkAddress))
      .format("org.apache.phoenix.spark")
      .load
    phoenixDF
  }

  /**
    * 获取 ZB8_STAT_LABEL表时间字段最大的值
    *
    * @return
    */
  def getNewTime(timeStep: Int, ss: SparkSession): util.List[String] = {
    val sql_maxEndTime: String = "SELECT  ENDTIME  FROM ZB8_STAT_LABEL ORDER BY ENDTIME DESC LIMIT 1"
    var newStartTime: Any = PhoenixDataSourceUtils.getSingleColValueFromPhoenix(ss, sql_maxEndTime, "ZB8_STAT_LABEL")
    if (newStartTime == null) {
      newStartTime = "2018092716"
    }
    val newStartTimeDate: Date = TimeUtils.dateStr2Date(newStartTime.toString, "yyyyMMddHH")
    val cal: Calendar = Calendar.getInstance
    cal.setTime(newStartTimeDate)
    cal.add(Calendar.HOUR_OF_DAY, timeStep)
    val endTimeInMillis: Long = TimeUtils.dateStr2TimeStemp(newStartTime.toString, "yyyyMMddHH")
    if ((System.currentTimeMillis - endTimeInMillis) < 3600000) {
      println("暂无最新数据需要计算，已将现有数据计算完成。")
      System.exit(0)
    }
    val newEndTime: String = TimeUtils.timeStemp2DateStr(String.valueOf(cal.getTimeInMillis), "yyyyMMddHH")
    util.Arrays.asList(newStartTime.toString, newEndTime)
  }

  def isHasNextDurData(ss: SparkSession, nextDurStartTime: String): Any = {
    val endTS: Long = TimeUtils.addTime(nextDurStartTime, "yyyyMMddHH", TimeUtils.HOUR, 1)
    val endStr: String = TimeUtils.timeStemp2DateStr(String.valueOf(endTS), "yyyyMMddHH")
    val sql =
      s"""
         |SELECT time FROM ZB8_CLICKLOG WHERE time>='${nextDurStartTime}' AND time<'${endStr}' LIMIT 1
   """.stripMargin
    val value: Any = PhoenixDataSourceUtils.getSingleColValueFromPhoenix(ss, sql, "ZB8_CLICKLOG")
    value
  }

}