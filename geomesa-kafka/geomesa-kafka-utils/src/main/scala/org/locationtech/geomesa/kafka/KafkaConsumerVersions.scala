/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kafka

import java.util.Collections

import org.apache.kafka.clients.consumer.{Consumer, ConsumerRebalanceListener}
import org.apache.kafka.common.TopicPartition

/**
  * Reflection wrapper for KafkaConsumer methods between kafka versions 0.9, 0.10, 1.0, 1.1, and 2.0
  */
object KafkaConsumerVersions {

  private val methods = classOf[Consumer[_, _]].getDeclaredMethods

  def seekToBeginning(consumer: Consumer[_, _], topic: TopicPartition): Unit = _seekToBeginning(consumer, topic)

  def pause(consumer: Consumer[_, _], topic: TopicPartition): Unit = _pause(consumer, topic)

  def resume(consumer: Consumer[_, _], topic: TopicPartition): Unit = _resume(consumer, topic)

  def subscribe(consumer: Consumer[_, _], topic: String): Unit = _subscribe(consumer, topic)

  def subscribe(consumer: Consumer[_, _], topic: String, listener: ConsumerRebalanceListener): Unit =
    _subscribeWithListener(consumer, topic, listener)

  def beginningOffsets(consumer: Consumer[_, _], topic: String, partitions: Seq[Int]): Map[Int, Long] =
    _beginningOffsets(consumer, topic, partitions)

  def endOffsets(consumer: Consumer[_, _], topic: String, partitions: Seq[Int]): Map[Int, Long] =
    _endOffsets(consumer, topic, partitions)

  private val _seekToBeginning: (Consumer[_, _], TopicPartition) => Unit = consumerTopicInvocation("seekToBeginning")

  private val _pause: (Consumer[_, _], TopicPartition) => Unit = consumerTopicInvocation("pause")

  private val _resume: (Consumer[_, _], TopicPartition) => Unit = consumerTopicInvocation("resume")

  private val _subscribe: (Consumer[_, _], String) => Unit = {
    val method = methods.find(m => m.getName == "subscribe" && m.getParameterCount == 1 &&
        m.getParameterTypes.apply(0).isAssignableFrom(classOf[java.util.List[_]])).getOrElse {
      throw new NoSuchMethodException(s"Couldn't find Consumer.subscribe method")
    }
    (consumer, topic) => method.invoke(consumer, Collections.singletonList(topic))
  }

  private val _subscribeWithListener: (Consumer[_, _], String, ConsumerRebalanceListener) => Unit = {
    val method = methods.find(m => m.getName == "subscribe" && m.getParameterCount == 2 &&
        m.getParameterTypes.apply(0).isAssignableFrom(classOf[java.util.List[_]]) &&
        m.getParameterTypes.apply(1).isAssignableFrom(classOf[ConsumerRebalanceListener])).getOrElse {
      throw new NoSuchMethodException(s"Couldn't find Consumer.subscribe method")
    }
    (consumer, topic, listener) => method.invoke(consumer, Collections.singletonList(topic), listener)
  }

  private val _beginningOffsets: (Consumer[_, _], String, Seq[Int]) => Map[Int, Long] = {
    import scala.collection.JavaConverters._
    // note: this method doesn't exist in 0.9, so may be null
    val method = methods.find(m => m.getName == "beginningOffsets" && m.getParameterCount == 1).orNull
    (consumer, topic, partitions) => {
      if (method == null) {
        throw new NoSuchMethodException(s"Couldn't find Consumer.beginningOffsets method")
      }
      val topicAndPartitions = new java.util.ArrayList[TopicPartition](partitions.length)
      partitions.foreach(p => topicAndPartitions.add(new TopicPartition(topic, p)))
      val offsets = method.invoke(consumer, topicAndPartitions).asInstanceOf[java.util.Map[TopicPartition, Long]]
      val result = Map.newBuilder[Int, Long]
      result.sizeHint(offsets.size())
      offsets.asScala.foreach { case (tp, o) => result += (tp.partition -> o) }
      result.result()
    }
  }

  private val _endOffsets: (Consumer[_, _], String, Seq[Int]) => Map[Int, Long] = {
    import scala.collection.JavaConverters._
    // note: this method doesn't exist in 0.9, so may be null
    val method = methods.find(m => m.getName == "endOffsets" && m.getParameterCount == 1).orNull
    (consumer, topic, partitions) => {
      if (method == null) {
        throw new NoSuchMethodException(s"Couldn't find Consumer.endOffsets method")
      }
      val topicAndPartitions = new java.util.ArrayList[TopicPartition](partitions.length)
      partitions.foreach(p => topicAndPartitions.add(new TopicPartition(topic, p)))
      val offsets = method.invoke(consumer, topicAndPartitions).asInstanceOf[java.util.Map[TopicPartition, Long]]
      val result = Map.newBuilder[Int, Long]
      result.sizeHint(offsets.size())
      offsets.asScala.foreach { case (tp, o) => result += (tp.partition -> o) }
      result.result()
    }
  }

  private def consumerTopicInvocation(name: String): (Consumer[_, _], TopicPartition) => Unit = {
    val method = methods.find(m => m.getName == name && m.getParameterCount == 1).getOrElse {
      throw new NoSuchMethodException(s"Couldn't find Consumer.$name method")
    }
    val binding = method.getParameterTypes.apply(0)

    if (binding == classOf[Array[TopicPartition]]) {
      (consumer, tp) => method.invoke(consumer, Array(tp))
    } else if (binding == classOf[java.util.Collection[TopicPartition]]) {
      (consumer, tp) => method.invoke(consumer, Collections.singletonList(tp))
    } else {
      throw new NoSuchMethodException(s"Couldn't find Consumer.$name method with correct parameters: $method")
    }
  }
}
