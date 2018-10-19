/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kudu.utils

import java.util.concurrent._

import org.apache.kudu.Schema
import org.apache.kudu.client._
import org.apache.kudu.util.Slice
import org.locationtech.geomesa.index.utils.AbstractBatchScan
import scala.collection.JavaConverters._

/**
  * Batch scanner for Kudu
  *
  * Note: RowResults are re-used inside the RowResultIterator, so we have to do
  * multi-threading at the RowResultIterator level
  *
  * @param client kudu client
  * @param table table to read
  * @param columns columns to read
  * @param ranges ranges to scan
  * @param predicates predicates
  * @param threads number of threads
  * @param buffer size of output buffer
  */
class KuduBatchScan(client: KuduClient, table: String, columns: Seq[String],
                    ranges: Seq[(Option[PartialRow], Option[PartialRow])], predicates: Seq[KuduPredicate],
                    threads: Int, buffer: Int) extends {
      // use early initialization to ensure table is open before scans kick off
      private val kuduTable = client.openTable(table)
      private val cols = columns.map(kuduTable.getSchema.getColumnIndex).asJava.asInstanceOf[java.util.List[Integer]]
    } with AbstractBatchScan[(Option[PartialRow], Option[PartialRow]), RowResultIterator](ranges, threads, buffer) {

  override protected def singletonSentinel: RowResultIterator = KuduBatchScan.Sentinel

  override protected def scan(range: (Option[PartialRow], Option[PartialRow]),
                              out: BlockingQueue[RowResultIterator]): Unit = {
    val builder = client.newScannerBuilder(kuduTable).setProjectedColumnIndexes(cols)
    range._1.foreach(builder.lowerBound)
    range._2.foreach(builder.exclusiveUpperBound)
    predicates.foreach(builder.addPredicate)
    val scanner = builder.build()
    try {
      while (scanner.hasMoreRows) {
        out.put(scanner.nextRows())
      }
    } finally {
      scanner.close()
    }
  }
}

object KuduBatchScan {
  private val Sentinel: RowResultIterator = {
    // use reflection to access the private constructor
    // private RowResultIterator(long ellapsedMillis, String tsUUID, Schema schema, int numRows, Slice bs, Slice indirectBs)
    val constructor = classOf[RowResultIterator].getDeclaredConstructor(classOf[Long], classOf[String],
      classOf[Schema], classOf[Int], classOf[Slice], classOf[Slice])
    constructor.setAccessible(true)
    constructor.newInstance(Long.box(0L), null, null, Int.box(0), null, null)
  }
}
