/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.stats

import org.geotools.data.{DataStore, Query, Transaction}
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.index.metadata.{GeoMesaMetadata, HasGeoMesaMetadata, NoOpMetadata}
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.stats.{SeqStat, Stat}
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter

/**
  * Allows for running of stats in a non-distributed fashion, and doesn't store any stats results
  *
  * @param ds datastore
  */
class UnoptimizedRunnableStats(val ds: DataStore with HasGeoMesaMetadata[String]) extends MetadataBackedStats {

  override protected val generateStats: Boolean = false

  override private [geomesa] val metadata: GeoMesaMetadata[Stat] = new NoOpMetadata[Stat]

  override def runStats[T <: Stat](sft: SimpleFeatureType, stats: String, filter: Filter): Seq[T] = {
    val stat = Stat(sft, stats)
    val query = new Query(sft.getTypeName, filter)
    try {
      val reader = CloseableIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT))
      try {
        reader.foreach(stat.observe)
      } finally {
        reader.close()
      }
      stat match {
        case s: SeqStat => s.stats.asInstanceOf[Seq[T]]
        case s => Seq(s).asInstanceOf[Seq[T]]
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error running stats query with stats '$stats' and filter '${filterToString(filter)}'", e)
        Seq.empty
    }
  }
}
