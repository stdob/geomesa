/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.index

import org.apache.accumulo.core.data.{Mutation, Range}
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.accumulo.index.AccumuloIndexAdapter.ScanConfig
import org.locationtech.geomesa.accumulo.iterators.Z3Iterator
import org.locationtech.geomesa.index.index.z3.{Z3Index, Z3IndexValues}
import org.opengis.feature.simple.SimpleFeatureType

// current version - new z-curve
case object Z3Index extends AccumuloFeatureIndex with AccumuloIndexAdapter
    with Z3Index[AccumuloDataStore, AccumuloFeature, Mutation, Range, ScanConfig] {

  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  val Z3IterPriority = 23

  override val version: Int = 5

  override val serializedWithId: Boolean = false

  override val hasPrecomputedBins: Boolean = true

  override protected def updateScanConfig(sft: SimpleFeatureType,
                                          config: ScanConfig,
                                          indexValues: Option[Z3IndexValues]): ScanConfig = {
    indexValues match {
      case None => config
      case Some(values) =>
        val zIter = Z3Iterator.configure(values, hasSplits = true, sft.isTableSharing, Z3IterPriority)
        config.copy(iterators = config.iterators :+ zIter)
    }
  }
}