/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase

import java.io._

import org.apache.hadoop.hbase.Coprocessor
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.filter.FilterList
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos
import org.geotools.data.Base64

import scala.collection.JavaConversions._

package object coprocessor {

  val FILTER_OPT = "filter"
  val SCAN_OPT   = "scan"

  lazy val AllCoprocessors: Seq[Class[_ <: Coprocessor]] = Seq(
    classOf[GeoMesaCoprocessor]
  )

  def deserializeOptions(bytes: Array[Byte]): Map[String, String] = {
    val byteIn = new ByteArrayInputStream(bytes)
    val in = new ObjectInputStream(byteIn)
    val map = in.readObject.asInstanceOf[Map[String, String]]
    map
  }

  @throws[IOException]
  def serializeOptions(map: Map[String, String]): Array[Byte] = {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(map)
    oos.flush()
    val output = baos.toByteArray
    oos.close()
    baos.close()
    output
  }

  def getScanFromOptions(options: Map[String, String]): List[Scan] = {
    if (options.containsKey(SCAN_OPT)) {
      val decoded = org.apache.hadoop.hbase.util.Base64.decode(options(SCAN_OPT))
      val clientScan = ClientProtos.Scan.parseFrom(decoded)
      List(ProtobufUtil.toScan(clientScan))
    } else {
      List(new Scan())
    }
  }

  def getFilterListFromOptions(options: Map[String, String]): FilterList = {
    if (options.containsKey(FILTER_OPT)) {
      FilterList.parseFrom(Base64.decode(options(FILTER_OPT)))
    } else {
      new FilterList()
    }
  }
}
