/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.iterators

import java.util.{UUID, Map => jMap}

import com.typesafe.scalalogging.LazyLogging
import org.apache.accumulo.core.client.IteratorSetting
import org.geotools.factory.Hints
import org.locationtech.geomesa.accumulo.AccumuloFeatureIndexType
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.features.SerializationOption.SerializationOptions
import org.locationtech.geomesa.features.kryo.KryoFeatureSerializer
import org.locationtech.geomesa.index.iterators.IteratorCache
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.geotools.{AttributeSpec, GeometryUtils}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.languageFeature.implicitConversions

class KryoLazyMapAggregatingIterator extends KryoLazyAggregatingIterator[mutable.Map[AnyRef, Int]] {

  import KryoLazyMapAggregatingIterator._

  var mapAttribute: Int = -1
  var serializer: KryoFeatureSerializer = _
  var featureToSerialize: SimpleFeature = _

  override def init(options: Map[String, String]): mutable.Map[AnyRef, Int] = {
    val attributeName = options(MAP_ATTRIBUTE)
    mapAttribute = sft.indexOf(attributeName)
    val mapSpec = createMapSft(sft, attributeName)
    val mapSft = IteratorCache.sft(mapSpec)
    val kryoOptions = if (index.serializedWithId) SerializationOptions.none else SerializationOptions.withoutId
    serializer = IteratorCache.serializer(mapSpec, kryoOptions)
    featureToSerialize = new ScalaSimpleFeature(mapSft, "", Array(null, GeometryUtils.zeroPoint))
    mutable.Map.empty[AnyRef, Int]
  }

  override def aggregateResult(sf: SimpleFeature, result: mutable.Map[AnyRef, Int]): Unit = {
    val currCounts = sf.getAttribute(mapAttribute).asInstanceOf[jMap[AnyRef, Int]].asScala
    sumNumericValueMutableMaps(Seq(currCounts), result)
  }

  override def encodeResult(result: mutable.Map[AnyRef, Int]): Array[Byte] = {
    featureToSerialize.setAttribute(0, result.asJava)
    serializer.serialize(featureToSerialize)
  }
}

object KryoLazyMapAggregatingIterator extends LazyLogging {

  import org.locationtech.geomesa.index.conf.QueryHints.RichHints

  val DEFAULT_PRIORITY = 30
  private val MAP_ATTRIBUTE = "map"

  /**
   * Creates an iterator config for the z3 density iterator
   */
  def configure(sft: SimpleFeatureType,
                index: AccumuloFeatureIndexType,
                filter: Option[Filter],
                hints: Hints,
                deduplicate: Boolean,
                priority: Int = DEFAULT_PRIORITY): IteratorSetting = {
    val mapAttribute = hints.getMapAggregatingAttribute
    val is = new IteratorSetting(priority, "map-aggregate-iter", classOf[KryoLazyMapAggregatingIterator])
    KryoLazyAggregatingIterator.configure(is, sft, index, filter, deduplicate, None)
    is.addOption(MAP_ATTRIBUTE, mapAttribute)
    is
  }

  def createMapSft(sft: SimpleFeatureType, mapAttribute: String) =
    s"${AttributeSpec(sft, sft.getDescriptor(mapAttribute)).toSpec},*geom:Point:srid=4326"

  def reduceMapAggregationFeatures(hints: Hints)
                                  (features: CloseableIterator[SimpleFeature]): CloseableIterator[SimpleFeature] = {
    val sft = hints.getReturnSft
    val mapIndex = sft.indexOf(hints.getMapAggregatingAttribute)

    val maps = features.map(_.getAttribute(mapIndex).asInstanceOf[jMap[AnyRef, Int]].asScala)

    if (maps.nonEmpty) {
      val reducedMap = sumNumericValueMutableMaps(maps.toIterable).toMap // to immutable map
      val attributes = Array(reducedMap.asJava, GeometryUtils.zeroPoint) // filler value as feature requires a geometry
      val result = new ScalaSimpleFeature(sft, UUID.randomUUID().toString, attributes)
      Iterator(result)
    } else {
      CloseableIterator.empty
    }
  }

  /**
    * Sums the values by key and returns a map containing all of the keys in the maps, with values
    * equal to the sum of all of the values for that key in the maps
    *
    * Sums with and aggregates the valueMaps into the aggregateInto map
    *
    * @param valueMaps input maps
    * @param aggregateInto container for results
    * @param num numeric type
    * @return the modified aggregateInto map containing the summed values
    */
  private def sumNumericValueMutableMaps[K, V](valueMaps: Iterable[collection.Map[K,V]],
                                               aggregateInto: mutable.Map[K,V] = scala.collection.mutable.Map.empty[K,V])
                                               (implicit num: Numeric[V]): scala.collection.mutable.Map[K, V] = {
    if (valueMaps.isEmpty) { aggregateInto } else {
      valueMaps.flatten.foldLeft(aggregateInto.withDefaultValue(num.zero)) { case (mapSoFar, (k, v)) =>
        mapSoFar += ((k, num.plus(v, mapSoFar(k))))
      }
    }
  }
}
