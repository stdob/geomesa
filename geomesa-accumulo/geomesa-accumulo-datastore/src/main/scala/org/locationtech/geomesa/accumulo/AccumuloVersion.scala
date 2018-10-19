/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo

import org.apache.accumulo.core.Constants
import org.apache.accumulo.core.client.admin.TimeType
import org.apache.accumulo.core.client.{Connector, TableExistsException}
import org.apache.accumulo.core.security.Authorizations
import org.apache.hadoop.io.Text

import scala.reflect.ClassTag

object AccumuloVersion extends Enumeration {
  type AccumuloVersion = Value
  val V15, V16, V17, V18 = Value

  lazy val accumuloVersion: AccumuloVersion = {
    if      (Constants.VERSION.startsWith("1.5")) V15
    else if (Constants.VERSION.startsWith("1.6")) V16
    else if (Constants.VERSION.startsWith("1.7")) V17
    else if (Constants.VERSION.startsWith("1.8")) V18
    else {
      throw new Exception(s"GeoMesa does not currently support Accumulo ${Constants.VERSION}.")
    }
  }

  lazy val AccumuloMetadataTableName: String = getMetadataTable
  lazy val AccumuloMetadataCF: Text = getMetadataColumnFamily
  lazy val EmptyAuths: Authorizations = getEmptyAuths

  def getMetadataTable: String = {
    accumuloVersion match {
      case V15 =>
        getTypeFromClass("org.apache.accumulo.core.Constants", "METADATA_TABLE_NAME")
      case V16 =>
        getTypeFromClass("org.apache.accumulo.core.metadata.MetadataTable", "NAME")
      case _ =>
        getTypeFromClass("org.apache.accumulo.core.metadata.MetadataTable", "NAME")
    }
  }

  def getMetadataColumnFamily: Text = {
    accumuloVersion match {
      case V15 =>
        getTypeFromClass("org.apache.accumulo.core.Constants", "METADATA_DATAFILE_COLUMN_FAMILY")
      case V16 =>
        getTypeFromClass("org.apache.accumulo.core.metadata.schema.MetadataSchema$TabletsSection$DataFileColumnFamily", "NAME")
      case _ =>
        getTypeFromClass("org.apache.accumulo.core.metadata.schema.MetadataSchema$TabletsSection$DataFileColumnFamily", "NAME")
    }
  }

  def getEmptyAuths: Authorizations = {
    accumuloVersion match {
      case V15 =>
        getTypeFromClass("org.apache.accumulo.core.Constants", "NO_AUTHS")
      case V16 =>
        getTypeFromClass("org.apache.accumulo.core.security.Authorizations", "EMPTY")
      case _ =>
        getTypeFromClass("org.apache.accumulo.core.security.Authorizations", "EMPTY")
    }
  }

  def ensureTableExists(connector: Connector, table: String, logical: Boolean = true): Unit = {
    val tableOps = connector.tableOperations()
    if (!tableOps.exists(table)) {
      try {
        val dot = table.indexOf('.')
        if (dot > 0) {
          ensureNamespaceExists(connector, table.substring(0, dot))
        }
        tableOps.create(table, true, if (logical) TimeType.LOGICAL else TimeType.MILLIS)
      } catch {
        // this can happen with multiple threads but shouldn't cause any issues
        case e: TableExistsException =>
        case e: Exception if e.getClass.getSimpleName == "NamespaceExistsException" =>
      }
    }
  }

  /**
   * Check for namespaces and create if needed.
   */
  def ensureNamespaceExists(connector: Connector, namespace: String): Unit = {
    require(accumuloVersion != V15, s"Table namespaces are not supported in Accumulo 1.5 - for namespace '$namespace'")
    val nsOps = classOf[Connector].getMethod("namespaceOperations").invoke(connector)
    if (!nameSpaceExists(nsOps, nsOps.getClass, namespace)) {
      val createMethod = nsOps.getClass.getMethod("create", classOf[String])
      createMethod.setAccessible(true)
      createMethod.invoke(nsOps, namespace)
    }
  }

  private def nameSpaceExists(nsOps: AnyRef, nsOpsClass: Class[_], ns: String): Boolean = {
    val existsMethod = nsOpsClass.getMethod("exists", classOf[String])
    existsMethod.setAccessible(true)
    existsMethod.invoke(nsOps, ns).asInstanceOf[Boolean]
  }

  private def getTypeFromClass[T: ClassTag](className: String, field: String): T = {
    val clazz = Class.forName(className)
    clazz.getDeclaredField(field).get(null).asInstanceOf[T]
  }
}