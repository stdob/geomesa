/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.index

import org.apache.accumulo.core.data.{Mutation, Range}
import org.locationtech.geomesa.accumulo.data.{AccumuloDataStore, AccumuloFeature}
import org.locationtech.geomesa.accumulo.index.AccumuloIndexAdapter.ScanConfig
import org.locationtech.geomesa.index.index.z3.XZ3Index


case object XZ3Index extends AccumuloFeatureIndex with AccumuloIndexAdapter
    with XZ3Index[AccumuloDataStore, AccumuloFeature, Mutation, Range, ScanConfig] {

  override val version: Int = 1

  override val serializedWithId: Boolean = false

  override val hasPrecomputedBins: Boolean = true
}
