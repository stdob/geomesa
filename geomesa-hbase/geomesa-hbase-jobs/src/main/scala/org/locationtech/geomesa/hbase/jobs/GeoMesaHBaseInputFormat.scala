/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.jobs

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{MultiTableInputFormat, TableInputFormat}
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce._
import org.locationtech.geomesa.filter.factory.FastFilterFactory
import org.locationtech.geomesa.hbase.data.HBaseConnectionPool
import org.locationtech.geomesa.hbase.index.{HBaseFeatureIndex, HBaseIndexAdapter}
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

/**
  * Input format that allows processing of simple features from GeoMesa based on a CQL query
  */
class GeoMesaHBaseInputFormat extends InputFormat[Text, SimpleFeature] with LazyLogging {

  val delegate = new MultiTableInputFormat

  var sft: SimpleFeatureType = _
  var table: HBaseIndexAdapter = _

  private def init(conf: Configuration): Unit = if (sft == null) {
    sft = GeoMesaConfigurator.getSchema(conf)
    table = HBaseFeatureIndex.index(GeoMesaConfigurator.getIndexIn(conf)).asInstanceOf[HBaseIndexAdapter]
    delegate.setConf(conf)
    // see TableMapReduceUtil.java
    HBaseConfiguration.merge(conf, HBaseConfiguration.create(conf))
    HBaseConnectionPool.configureSecurity(conf)
    conf.set(TableInputFormat.INPUT_TABLE, GeoMesaConfigurator.getTable(conf))
  }

  /**
    * Gets splits for a job.
    */
  override def getSplits(context: JobContext): java.util.List[InputSplit] = {
    init(context.getConfiguration)
    val splits = delegate.getSplits(context)
    logger.debug(s"Got ${splits.size()} splits")
    splits
  }

  override def createRecordReader(split: InputSplit,
                                  context: TaskAttemptContext): RecordReader[Text, SimpleFeature] = {
    init(context.getConfiguration)
    val rr = delegate.createRecordReader(split, context)
    val ecql = GeoMesaConfigurator.getFilter(context.getConfiguration).map(FastFilterFactory.toFilter(sft, _))
    val transform = GeoMesaConfigurator.getTransformSchema(context.getConfiguration)
    // TODO GEOMESA-2300 support local filtering
    new HBaseGeoMesaRecordReader(table, sft, ecql, transform, rr, true)
  }
}

class HBaseGeoMesaRecordReader(table: HBaseIndexAdapter,
                               sft: SimpleFeatureType,
                               ecql: Option[Filter],
                               transform: Option[SimpleFeatureType],
                               reader: RecordReader[ImmutableBytesWritable, Result],
                               remoteFiltering: Boolean)
    extends RecordReader[Text, SimpleFeature] with LazyLogging {

  import scala.collection.JavaConverters._

  private val results = new Iterator[Result] {

    private var current: Result = _

    override def hasNext: Boolean = {
      if (current != null) {
        true
      } else if (reader.nextKeyValue()) {
        current = reader.getCurrentValue
        true
      } else {
        false
      }
    }

    override def next(): Result = {
      val res = current
      current = null
      res
    }
  }

  private val features =
    if (remoteFiltering) {
      // transforms and filter are pushed down, so we don't have to deal with them here
      table.resultsToFeatures(sft, transform.getOrElse(sft))(results)
    } else {
      // TODO GEOMESA-2300 this doesn't handle anything beyond simple attribute projection
      val transforms = transform.map { tsft =>
        (tsft.getAttributeDescriptors.asScala.map(d => s"${d.getLocalName}=${d.getLocalName}").mkString(";"), tsft)
      }
      table.resultsToFeatures(sft, ecql, transforms)(results)
    }

  private var staged: SimpleFeature = _

  override def initialize(split: InputSplit, context: TaskAttemptContext): Unit = reader.initialize(split, context)

  override def getProgress: Float = reader.getProgress

  override def nextKeyValue(): Boolean = {
    if (features.hasNext) {
      staged = features.next
      true
    } else {
      false
    }
  }

  override def getCurrentValue: SimpleFeature = staged

  override def getCurrentKey = new Text(staged.getID)

  override def close(): Unit = reader.close()
}