/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.coprocessor.aggregators

import org.apache.commons.codec.binary.Base64
import org.geotools.factory.Hints
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.hbase._
import org.locationtech.geomesa.hbase.coprocessor.GeoMesaCoprocessor
import org.locationtech.geomesa.index.iterators.StatsScan
import org.locationtech.geomesa.utils.geotools.GeometryUtils
import org.locationtech.geomesa.utils.stats._
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

class HBaseStatsAggregator extends HBaseAggregator[Stat] with StatsScan

object HBaseStatsAggregator {

  def bytesToFeatures(bytes : Array[Byte]): SimpleFeature = {
    val sf = new ScalaSimpleFeature(StatsScan.StatsSft, "")
    sf.setAttribute(0, Base64.encodeBase64URLSafeString(bytes))
    sf.setAttribute(1, GeometryUtils.zeroPoint)
    sf
  }

  def configure(sft: SimpleFeatureType,
                index: HBaseFeatureIndexType,
                filter: Option[Filter],
                hints: Hints): Map[String, String] = {
    StatsScan.configure(sft, index, filter, hints) ++
      Map(GeoMesaCoprocessor.AggregatorClass -> classOf[HBaseStatsAggregator].getName)
  }
}
