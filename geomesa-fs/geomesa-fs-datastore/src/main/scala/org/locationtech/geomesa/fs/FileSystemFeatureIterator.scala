/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs

import java.io.Closeable
import java.util.concurrent._

import org.geotools.data.Query
import org.locationtech.geomesa.fs.storage.api.{FileSystemReader, FileSystemStorage}
import org.opengis.feature.simple.SimpleFeature

/**
  * Iterator for querying file system storage
  *
  * Note: implements Closeable and not AutoCloseable so that DelegateFeatureIterator will close it properly
  *
  * @param storage storage impl
  * @param query query
  * @param readThreads threads
  */
class FileSystemFeatureIterator(storage: FileSystemStorage, query: Query, readThreads: Int)
    extends java.util.Iterator[SimpleFeature] with Closeable {

  private val partitions = {
    val metadata = storage.getPartitions(query.getFilter)
    val result = new java.util.ArrayList[String](metadata.size())
    val iter = metadata.iterator()
    while (iter.hasNext) {
      result.add(iter.next.name)
    }
    result
  }

  private val iter: FileSystemReader =
    if (partitions.isEmpty) {
      FileSystemFeatureIterator.EmptyReader
    } else {
      storage.getReader(partitions, query, readThreads)
    }

  override def hasNext: Boolean = iter.hasNext
  override def next(): SimpleFeature = iter.next()
  override def close(): Unit = iter.close()
}

object FileSystemFeatureIterator {
  object EmptyReader extends FileSystemReader {
    override def next(): SimpleFeature = throw new NoSuchElementException
    override def hasNext: Boolean = false
    override def close(): Unit = {}
    override def close(wait: Long, unit: TimeUnit): Boolean = true
  }
}
