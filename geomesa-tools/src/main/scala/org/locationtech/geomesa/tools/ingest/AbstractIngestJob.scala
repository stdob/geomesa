/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.ingest

import java.io.File

import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, JobStatus, Mapper}
import org.geotools.data.DataUtilities
import org.geotools.factory.Hints
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.locationtech.geomesa.jobs.mapreduce.{GeoMesaOutputFormat, JobWithLibJars}
import org.locationtech.geomesa.tools.Command
import org.locationtech.geomesa.tools.ingest.AbstractIngest.StatusCallback
import org.opengis.feature.simple.SimpleFeature

/**
 * Abstract class that handles configuration and tracking of the remote job
 */
abstract class AbstractIngestJob(dsParams: Map[String, String],
                                 typeName: String,
                                 paths: Seq[String],
                                 libjarsFile: String,
                                 libjarsPaths: Iterator[() => Seq[File]]) extends JobWithLibJars {

  def inputFormatClass: Class[_ <: FileInputFormat[_, SimpleFeature]]
  def written(job: Job): Long
  def failed(job: Job): Long

  def run(statusCallback: StatusCallback): (Long, Long) = {

    val job = Job.getInstance(new Configuration, "GeoMesa Tools Ingest")

    setLibJars(job, libjarsFile, libjarsPaths)

    configureJob(job)

    Command.user.info("Submitting job - please wait...")
    job.submit()
    Command.user.info(s"Tracking available at ${job.getStatus.getTrackingUrl}")

    def counters = Seq(("ingested", written(job)), ("failed", failed(job)))

    while (!job.isComplete) {
      if (job.getStatus.getState != JobStatus.State.PREP) {
        // we don't have any reducers, just track mapper progress
        statusCallback("", job.mapProgress(), counters, done = false)
      }
      Thread.sleep(500)
    }
    statusCallback("", job.mapProgress(), counters, done = true)

    if (!job.isSuccessful) {
      Command.user.error(s"Job failed with state ${job.getStatus.getState} due to: ${job.getStatus.getFailureInfo}")
    }

    (written(job), failed(job))
  }

  def configureJob(job: Job): Unit = {
    job.setJarByClass(getClass)
    job.setMapperClass(classOf[IngestMapper])
    job.setInputFormatClass(inputFormatClass)
    job.setOutputFormatClass(classOf[GeoMesaOutputFormat])
    job.setMapOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[ScalaSimpleFeature])
    job.setNumReduceTasks(0)
    job.getConfiguration.set("mapred.map.tasks.speculative.execution", "false")
    job.getConfiguration.set("mapred.reduce.tasks.speculative.execution", "false")
    job.getConfiguration.set("mapreduce.job.user.classpath.first", "true")

    FileInputFormat.setInputPaths(job, paths.mkString(","))

    GeoMesaConfigurator.setFeatureTypeOut(job.getConfiguration, typeName)
    GeoMesaOutputFormat.configureDataStore(job, dsParams)
  }
}

/**
 * Takes the input and writes it to the output - all our main work is done in the input format
 */
class IngestMapper extends Mapper[LongWritable, SimpleFeature, Text, SimpleFeature] with LazyLogging {

  type Context = Mapper[LongWritable, SimpleFeature, Text, SimpleFeature]#Context

  private val text: Text = new Text

  override def map(key: LongWritable, sf: SimpleFeature, context: Context): Unit = {
    logger.debug(s"map key ${key.toString}, map value ${DataUtilities.encodeFeature(sf)}")
    sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
    context.write(text, sf)
  }
}
