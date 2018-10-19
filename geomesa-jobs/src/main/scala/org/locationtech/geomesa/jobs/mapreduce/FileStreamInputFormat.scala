/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.jobs.mapreduce

import java.io.{Closeable, InputStream}

import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, Seekable}
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.io.compress.{CodecPool, CompressionCodecFactory, Decompressor}
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

/**
 * Input format that gives us access to the entire file as a byte stream via the record reader.
 */
abstract class FileStreamInputFormat extends FileInputFormat[LongWritable, SimpleFeature] {

  type SFRR = RecordReader[LongWritable, SimpleFeature]

  override protected def isSplitable(context: JobContext, filename: Path): Boolean = false

  override def createRecordReader(split: InputSplit, context: TaskAttemptContext): SFRR =
    createRecordReader()

  /**
   * Abstract method to create a subclass of record reader
   *
   * @return file stream record reader implementation
   */
  def createRecordReader(): FileStreamRecordReader
}

object FileStreamInputFormat {

  val SftKey        = "org.locationtech.geomesa.jobs.ingest.sft"
  val TypeNameKey   = "org.locationtech.geomesa.jobs.ingest.sft.name"

  /**
   * Set the simple feature type in the job configuration for distributed access.
   *
   * @param job job
   * @param sft simple feature type
   */
  def setSft(job: Job, sft: SimpleFeatureType): Unit = setSft(job.getConfiguration, sft)

  /**
    * Set the simple feature type in the job configuration for distributed access.
    *
    * @param conf job conf
    * @param sft simple feature type
    */
  def setSft(conf: Configuration, sft: SimpleFeatureType): Unit = {
    conf.set(SftKey, SimpleFeatureTypes.encodeType(sft))
    conf.set(TypeNameKey, sft.getTypeName)
  }

  /**
   * Gets the simple feature type previously set with setSft
   *
   * @param conf job configuration
   * @return simple feature type
   */
  def getSft(conf: Configuration): SimpleFeatureType = {
    val typeName = conf.get(FileStreamInputFormat.TypeNameKey)
    SimpleFeatureTypes.createType(typeName, conf.get(FileStreamInputFormat.SftKey))
  }
}

/**
 * Base class for operating on file input streams. Abstracts away most of the m/r framework.
 */
abstract class FileStreamRecordReader() extends RecordReader[LongWritable, SimpleFeature] with LazyLogging {

  private var dec: Decompressor = null
  private var stream: InputStream with Seekable = null
  private var iter: Iterator[SimpleFeature] with Closeable = null
  private var length: Float = 0

  private val curKey = new LongWritable(0)
  private var curValue: SimpleFeature = null

  def createIterator(stream: InputStream with Seekable,
                     filePath: Path,
                     context: TaskAttemptContext): Iterator[SimpleFeature] with Closeable

  override def getProgress: Float = {
    if (length == 0) 0.0f else math.min(1.0f, stream.getPos / length)
  }

  override def nextKeyValue(): Boolean = {
    if (iter.hasNext) {
      curKey.set(curKey.get() + 1)
      curValue = iter.next()
      true
    } else {
      false
    }
  }

  override def getCurrentValue: SimpleFeature = curValue

  override def initialize(split: InputSplit, context: TaskAttemptContext): Unit = {
    val job   = context.getConfiguration
    val path  = split.asInstanceOf[FileSplit].getPath
    val codec = new CompressionCodecFactory(job).getCodec(path)
    val fs    = path.getFileSystem(job)

    length = split.getLength.toFloat
    stream =
      if (codec != null) {
        dec = CodecPool.getDecompressor(codec)
        codec.createInputStream(fs.open(path), dec)
      } else {
        fs.open(path)
      }
    iter = createIterator(stream, path, context)
    logger.info(s"Initialized record reader on split ${path.toString}")
  }

  override def getCurrentKey: LongWritable = curKey

  override def close(): Unit = {
    IOUtils.closeQuietly(iter)
    IOUtils.closeQuietly(stream)
    if (dec != null) {
      CodecPool.returnDecompressor(dec)
    }
  }
}
