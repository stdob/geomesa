/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.storage.orc.jobs

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.orc.mapred.OrcStruct
import org.apache.orc.mapreduce.OrcInputFormat
import org.geotools.data.Query
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.features.{ScalaSimpleFeature, TransformSimpleFeature}
import org.locationtech.geomesa.fs.storage.common.jobs.StorageConfiguration
import org.locationtech.geomesa.fs.storage.orc.OrcFileSystemReader
import org.locationtech.geomesa.fs.storage.orc.jobs.OrcSimpleFeatureInputFormat.OrcSimpleFeatureRecordReader
import org.locationtech.geomesa.fs.storage.orc.utils.OrcInputFormatReader
import org.locationtech.geomesa.index.planning.QueryRunner
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

class OrcSimpleFeatureInputFormat extends FileInputFormat[Void, SimpleFeature] {

  private val delegate = new OrcInputFormat[OrcStruct]

  override def createRecordReader(split: InputSplit, context: TaskAttemptContext): OrcSimpleFeatureRecordReader = {
    val delegateReader = delegate.createRecordReader(split, context)
    new OrcSimpleFeatureRecordReader(delegateReader, context.getConfiguration)
  }

  override def getSplits(context: JobContext): java.util.List[InputSplit] = delegate.getSplits(context)
}

object OrcSimpleFeatureInputFormat {

  val FilterConfig              = "geomesa.fs.filter"

  val TransformSpecConfig       = "geomesa.fs.transform.spec"
  val TransformDefinitionConfig = "geomesa.fs.transform.defs"

  val ReadColumnsConfig         = "geomesa.fs.orc.columns"

  /**
    * Configure an input format
    *
    * @param conf hadoop configuration
    * @param sft simple feature type being read
    * @param filter ECQL filter
    * @param transforms result transform
    */
  def configure(conf: Configuration, sft: SimpleFeatureType, filter: Filter, transforms: Array[String] = null): Unit = {
    import org.locationtech.geomesa.index.conf.QueryHints.RichHints

    val query = new Query(sft.getTypeName, filter, transforms)
    val q = QueryRunner.default.configureQuery(sft, query)
    val f = Option(q.getFilter).filter(_ != Filter.INCLUDE)
    val transform = q.getHints.getTransform

    StorageConfiguration.setSft(conf, sft)

    f.map(ECQL.toCQL).foreach(conf.set(FilterConfig, _))
    transform.foreach { case (tdefs, tsft) =>
      conf.set(TransformDefinitionConfig, tdefs)
      conf.set(TransformSpecConfig, SimpleFeatureTypes.encodeType(tsft, includeUserData = true))
    }

    val options = OrcFileSystemReader.readOptions(sft, f, transform)
    options.pushDown.foreach { case (sargs, cols) => OrcInputFormat.setSearchArgument(conf, sargs, cols) }
    // TODO no way to set Orc.include cols?
    options.columns.foreach(c => conf.set(ReadColumnsConfig, c.map(sft.indexOf).mkString(",")))
  }

  def getFilter(conf: Configuration): Option[Filter] = Option(conf.get(FilterConfig)).map(ECQL.toFilter)

  def getTransforms(conf: Configuration): Option[(String, SimpleFeatureType)] = {
    for {
      defs <- Option(conf.get(TransformDefinitionConfig))
      spec <- Option(conf.get(TransformSpecConfig))
    } yield {
      (defs, SimpleFeatureTypes.createType("", spec))
    }
  }

  def getReadColumns(conf: Configuration): Option[Set[Int]] =
    Option(conf.get(ReadColumnsConfig))
      .filter(_.nonEmpty)
      .map(_.split(",").map(_.toInt).toSet)

  class OrcSimpleFeatureRecordReader(delegate: RecordReader[NullWritable, OrcStruct], conf: Configuration)
      extends RecordReader[Void, SimpleFeature] {

    private val sft = StorageConfiguration.getSft(conf)
    private val filter = getFilter(conf)

    private val feature = new ScalaSimpleFeature(sft, "")

    private val setAttributes = OrcInputFormatReader(sft, getReadColumns(conf))

    private val current: SimpleFeature =
      getTransforms(conf)
          .map { case (tdefs, tsft) => TransformSimpleFeature(sft, tsft, tdefs).setFeature(feature) }
          .getOrElse(feature)

    override def initialize(split: InputSplit, context: TaskAttemptContext): Unit =
      delegate.initialize(split, context)

    override def nextKeyValue(): Boolean = {
      while (delegate.nextKeyValue()) {
        setAttributes(delegate.getCurrentValue, feature)
        if (filter.forall(_.evaluate(feature))) {
          return true
        }
      }
      false
    }

    override def getCurrentKey: Void = null

    override def getCurrentValue: SimpleFeature = current

    override def getProgress: Float = delegate.getProgress

    override def close(): Unit = delegate.close()
  }
}
