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

package org.apache.spark.streaming.kafka010

import java.{ util => ju }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition

import org.apache.spark.internal.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.scheduler.{RateController, StreamInputInfo}
import org.apache.spark.streaming.scheduler.rate.RateEstimator

/**
  *  A DStream where
  * each given Kafka topic/partition corresponds to an RDD partition.
  * The spark configuration spark.streaming.kafka.maxRatePerPartition gives the maximum number
  *  of messages
  * per second that each '''partition''' will accept.
  * @param locationStrategy In most cases, pass in [[LocationStrategies.PreferConsistent]],
  *   see [[LocationStrategy]] for more details.
  * @param consumerStrategy In most cases, pass in [[ConsumerStrategies.Subscribe]],
  *   see [[ConsumerStrategy]] for more details
  * @param ppc configuration of settings such as max rate on a per-partition basis.
  *   see [[PerPartitionConfig]] for more details.
  * @tparam K type of Kafka message key
  * @tparam V type of Kafka message value
  */
private[spark] class DirectKafkaInputDStream[K, V](
                                                    _ssc: StreamingContext,
                                                    locationStrategy: LocationStrategy,
                                                    consumerStrategy: ConsumerStrategy[K, V],
                                                    ppc: PerPartitionConfig
                                                  ) extends InputDStream[ConsumerRecord[K, V]](_ssc) with Logging with CanCommitOffsets {

  val executorKafkaParams = {
    val ekp = new ju.HashMap[String, Object](consumerStrategy.executorKafkaParams)
    KafkaUtils.fixKafkaParams(ekp)
    ekp
  }

  protected var currentOffsets = Map[TopicPartition, Long]()

  @transient private var kc: Consumer[K, V] = null
  def consumer(): Consumer[K, V] = this.synchronized {
    if (null == kc) {
      kc = consumerStrategy.onStart(currentOffsets.mapValues(l => new java.lang.Long(l)).asJava)
    }
    kc
  }

  override def persist(newLevel: StorageLevel): DStream[ConsumerRecord[K, V]] = {
    logError("Kafka ConsumerRecord is not serializable. " +
      "Use .map to extract fields before calling .persist or .window")
    super.persist(newLevel)
  }

  protected def getBrokers = {
    val c = consumer
    val result = new ju.HashMap[TopicPartition, String]()
    val hosts = new ju.HashMap[TopicPartition, String]()
    val assignments = c.assignment().iterator()
    while (assignments.hasNext()) {
      val tp: TopicPartition = assignments.next()
      if (null == hosts.get(tp)) {
        val infos = c.partitionsFor(tp.topic).iterator()
        while (infos.hasNext()) {
          val i = infos.next()
          hosts.put(new TopicPartition(i.topic(), i.partition()), i.leader.host())
        }
      }
      result.put(tp, hosts.get(tp))
    }
    result
  }

  protected def getPreferredHosts: ju.Map[TopicPartition, String] = {
    locationStrategy match {
      case PreferBrokers => getBrokers
      case PreferConsistent => ju.Collections.emptyMap[TopicPartition, String]()
      case PreferFixed(hostMap) => hostMap
    }
  }

  // Keep this consistent with how other streams are named (e.g. "Flume polling stream [2]")
  private[streaming] override def name: String = s"Kafka 0.10 direct stream [$id]"

  protected[streaming] override val checkpointData =
    new DirectKafkaInputDStreamCheckpointData


  /**
    * Asynchronously maintains & sends new rate limits to the receiver through the receiver tracker.
    */
  override protected[streaming] val rateController: Option[RateController] = {
    if (RateController.isBackPressureEnabled(ssc.conf)) {
      Some(new DirectKafkaRateController(id,
        RateEstimator.create(ssc.conf, context.graph.batchDuration)))
    } else {
      None
    }
  }

  protected[streaming] def maxMessagesPerPartition(
                                                    offsets: Map[TopicPartition, Long]): Option[Map[TopicPartition, Long]] = {
    val estimatedRateLimit = rateController.map(_.getLatestRate())

    // calculate a per-partition rate limit based on current lag
    //    基于当前lag，来计算每个分区的速率。
    val effectiveRateLimitPerPartition = estimatedRateLimit.filter(_ > 0) match {
      case Some(rate) =>
        //        求出每个分区滞后的消息offset
        val lagPerPartition = offsets.map { case (tp, offset) =>
          tp -> Math.max(offset - currentOffsets(tp), 0)
        }
        val totalLag = lagPerPartition.values.sum

        lagPerPartition.map { case (tp, lag) =>
          //          取出分区配置的最大限速 速率，由参数 spark.streaming.kafka.maxRatePerPartition 配置
          val maxRateLimitPerPartition = ppc.maxRatePerPartition(tp)
          //          计算背压rate
          val backpressureRate = Math.round(lag / totalLag.toFloat * rate)

          //          计算每个分区要消费的最大offset，假如配置了spark.streaming.kafka.maxRatePerPartition，就取背压速率和最大速率的最小值，假如没有最大限速，就取背压速率

          tp -> (if (maxRateLimitPerPartition > 0) {
            Math.min(backpressureRate, maxRateLimitPerPartition)} else backpressureRate)
        }
      //       假如PID计算器没有计算出大于0的速率，或者没有使用(新增分区)，那么就采用配置中的最大限速速率
      case None => offsets.map { case (tp, offset) => tp -> ppc.maxRatePerPartition(tp) }
    }

    //    将batch时间转化为s，并且乘以前面算的速率，得到最大oofset。
    if (effectiveRateLimitPerPartition.values.sum > 0) {
      val secsPerBatch = context.graph.batchDuration.milliseconds.toDouble / 1000
      Some(effectiveRateLimitPerPartition.map {
        case (tp, limit) => tp -> (secsPerBatch * limit).toLong
      })
    } else {
      None
    }
  }

  /**
    * The concern here is that poll might consume messages despite being paused,
    * which would throw off consumer position.  Fix position if this happens.
    */
  private def paranoidPoll(c: Consumer[K, V]): Unit = {
    val msgs = c.poll(0)
    if (!msgs.isEmpty) {
      // position should be minimum offset per topicpartition
      msgs.asScala.foldLeft(Map[TopicPartition, Long]()) { (acc, m) =>
        val tp = new TopicPartition(m.topic, m.partition)
        val off = acc.get(tp).map(o => Math.min(o, m.offset)).getOrElse(m.offset)
        acc + (tp -> off)
      }.foreach { case (tp, off) =>
        logInfo(s"poll(0) returned messages, seeking $tp to $off to compensate")
        c.seek(tp, off)
      }
    }
  }

  /**
    * Returns the latest (highest) available offsets, taking new partitions into account.
    *  返回最大可提供offset，并将新增分区生效
    */
  protected def latestOffsets(): Map[TopicPartition, Long] = {
    val c = consumer
    paranoidPoll(c)
    // 获取所有的分区信息
    val parts = c.assignment().asScala

    // make sure new partitions are reflected in currentOffsets
    // 做差获取新增的分区信息
    val newPartitions = parts.diff(currentOffsets.keySet)
    // position for new partitions determined by auto.offset.reset if no commit
    // 新分区消费位置，没有记录的化是由auto.offset.reset决定
    currentOffsets = currentOffsets ++ newPartitions.map(tp => tp -> c.position(tp)).toMap
    // don't want to consume messages, so pause
    // 不会在这里获取数据，所以要pause
    c.pause(newPartitions.asJava)
    // find latest available offsets
    //    找到kafka可提供的最大offsets
    c.seekToEnd(currentOffsets.keySet.asJava)
    parts.map(tp => tp -> c.position(tp)).toMap
  }

  // limits the maximum number of messages per partition
  //  限制每个分区处理的最大消息条数不超过kafka 分区里的最大offset
  protected def clamp(
                       offsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] = {

    maxMessagesPerPartition(offsets).map { mmp =>
      mmp.map { case (tp, messages) =>
        val uo = offsets(tp) // kafka的最大偏移
        tp -> Math.min(currentOffsets(tp) + messages, uo) // 限制最大偏移应该小于等于kafka最大offset
      }
    }.getOrElse(offsets)
  }

  override def compute(validTime: Time): Option[KafkaRDD[K, V]] = {
    //    获取当前生成job，要用到的KafkaRDD每个分区最大消费偏移值
    val untilOffsets = clamp(latestOffsets())
    val offsetRanges = untilOffsets.map { case (tp, uo) =>
      val fo = currentOffsets(tp)
      OffsetRange(tp.topic, tp.partition, 0, 10)
    }
    val useConsumerCache = context.conf.getBoolean("spark.streaming.kafka.consumer.cache.enabled",
      true)
    val rdd = new KafkaRDD[K, V](context.sparkContext, executorKafkaParams, offsetRanges.toArray,
      getPreferredHosts, useConsumerCache)

    // Report the record number and metadata of this batch interval to InputInfoTracker.
    val description = offsetRanges.filter { offsetRange =>
      // Don't display empty ranges.
      offsetRange.fromOffset != offsetRange.untilOffset
    }.map { offsetRange =>
      s"topic: ${offsetRange.topic}\tpartition: ${offsetRange.partition}\t" +
        s"offsets: ${offsetRange.fromOffset} to ${offsetRange.untilOffset}"
    }.mkString("\n")
    // Copy offsetRanges to immutable.List to prevent from being modified by the user
    val metadata = Map(
      "offsets" -> offsetRanges.toList,
      StreamInputInfo.METADATA_KEY_DESCRIPTION -> description)
    val inputInfo = StreamInputInfo(id, rdd.count, metadata)
    ssc.scheduler.inputInfoTracker.reportInfo(validTime, inputInfo)

    currentOffsets = untilOffsets
    commitAll()
    Some(rdd)
  }

  override def start(): Unit = {
    val c = consumer
    paranoidPoll(c)
    if (currentOffsets.isEmpty) {
      currentOffsets = c.assignment().asScala.map { tp =>
        tp -> c.position(tp)
      }.toMap
    }

    // don't actually want to consume any messages, so pause all partitions
    c.pause(currentOffsets.keySet.asJava)
  }

  override def stop(): Unit = this.synchronized {
    if (kc != null) {
      kc.close()
    }
  }

  protected val commitQueue = new ConcurrentLinkedQueue[OffsetRange]
  protected val commitCallback = new AtomicReference[OffsetCommitCallback]

  /**
    * Queue up offset ranges for commit to Kafka at a future time.  Threadsafe.
    * @param offsetRanges The maximum untilOffset for a given partition will be used at commit.
    */
  def commitAsync(offsetRanges: Array[OffsetRange]): Unit = {
    commitAsync(offsetRanges, null)
  }

  /**
    * Queue up offset ranges for commit to Kafka at a future time.  Threadsafe.
    * @param offsetRanges The maximum untilOffset for a given partition will be used at commit.
    * @param callback Only the most recently provided callback will be used at commit.
    */
  def commitAsync(offsetRanges: Array[OffsetRange], callback: OffsetCommitCallback): Unit = {
    commitCallback.set(callback)
    commitQueue.addAll(ju.Arrays.asList(offsetRanges: _*))
  }

  protected def commitAll(): Unit = {
    val m = new ju.HashMap[TopicPartition, OffsetAndMetadata]()
    var osr = commitQueue.poll()
    while (null != osr) {
      val tp = osr.topicPartition
      val x = m.get(tp)
      val offset = if (null == x) { osr.untilOffset } else { Math.max(x.offset, osr.untilOffset) }
      m.put(tp, new OffsetAndMetadata(offset))
      osr = commitQueue.poll()
    }
    if (!m.isEmpty) {
      consumer.commitAsync(m, commitCallback.get)
    }
  }

  private[streaming]
  class DirectKafkaInputDStreamCheckpointData extends DStreamCheckpointData(this) {
    def batchForTime: mutable.HashMap[Time, Array[(String, Int, Long, Long)]] = {
      data.asInstanceOf[mutable.HashMap[Time, Array[OffsetRange.OffsetRangeTuple]]]
    }

    override def update(time: Time): Unit = {
      batchForTime.clear()
      generatedRDDs.foreach { kv =>
        val a = kv._2.asInstanceOf[KafkaRDD[K, V]].offsetRanges.map(_.toTuple).toArray
        batchForTime += kv._1 -> a
      }
    }

    override def cleanup(time: Time): Unit = { }

    override def restore(): Unit = {
      batchForTime.toSeq.sortBy(_._1)(Time.ordering).foreach { case (t, b) =>
        logInfo(s"Restoring KafkaRDD for time $t ${b.mkString("[", ", ", "]")}")
        generatedRDDs += t -> new KafkaRDD[K, V](
          context.sparkContext,
          executorKafkaParams,
          b.map(OffsetRange(_)),
          getPreferredHosts,
          // during restore, it's possible same partition will be consumed from multiple
          // threads, so do not use cache.
          false
        )
      }
    }
  }

  /**
    * A RateController to retrieve the rate from RateEstimator.
    */
  private[streaming] class DirectKafkaRateController(id: Int, estimator: RateEstimator)
    extends RateController(id, estimator) {
    override def publish(rate: Long): Unit = ()
  }
}
