/***********************************************************************
 * Copyright (c) 2017-2018 IBM
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa

import org.locationtech.geomesa.cassandra.data.{CassandraDataStore, CassandraFeature}
import org.locationtech.geomesa.index.api._
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureWriter._
import org.locationtech.geomesa.index.geotools.{GeoMesaDataStore, GeoMesaFeatureWriter}
import org.locationtech.geomesa.index.planning.QueryPlanner
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty

package object cassandra {
  type CassandraDataStoreType = GeoMesaDataStore[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraFeatureIndexType = GeoMesaFeatureIndex[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraFilterPlanType = FilterPlan[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraFilterStrategyType = FilterStrategy[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraQueryPlannerType = QueryPlanner[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraQueryPlanType = QueryPlan[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraIndexManagerType = GeoMesaIndexManager[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraFeatureWriterFactoryType = FeatureWriterFactory[CassandraDataStore, CassandraFeature, Seq[RowValue]]
  type CassandraFeatureWriterType = GeoMesaFeatureWriter[CassandraDataStore, CassandraFeature, Seq[RowValue], String]
  type CassandraTableFeatureWriterType = TableFeatureWriter[CassandraDataStore, CassandraFeature, Seq[RowValue], String]
  type CassandraPartitionedFeatureWriterType = PartitionedFeatureWriter[CassandraDataStore, CassandraFeature, Seq[RowValue], String]
  type CassandraAppendFeatureWriterType = GeoMesaAppendFeatureWriter[CassandraDataStore, CassandraFeature, Seq[RowValue], String]
  type CassandraModifyFeatureWriterType = GeoMesaModifyFeatureWriter[CassandraDataStore, CassandraFeature, Seq[RowValue], String]

  case class NamedColumn(name: String, i: Int, cType: String, jType: Class[_], partition: Boolean = false)
  case class RowValue(column: NamedColumn, value: AnyRef)
  case class RowRange(column: NamedColumn, start: Any, end: Any)
  case class CassandraRow(table: String, values: Seq[RowValue])

  object CassandraSystemProperties {
    val ReadTimeoutMillis       = SystemProperty("geomesa.cassandra.read.timeout", "30 seconds")
    val ConnectionTimeoutMillis = SystemProperty("geomesa.cassandra.connection.timeout", "30 seconds")
  }
}
