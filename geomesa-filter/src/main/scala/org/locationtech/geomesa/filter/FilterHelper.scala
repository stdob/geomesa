/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.filter

import java.time.{ZoneOffset, ZonedDateTime}
import java.util.{Date, Locale}

import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom._
import org.geotools.data.DataUtilities
import org.geotools.filter.spatial.BBOXImpl
import org.locationtech.geomesa.filter.Bounds.Bound
import org.locationtech.geomesa.filter.expression.AttributeExpression.{FunctionLiteral, PropertyLiteral}
import org.locationtech.geomesa.filter.visitor.IdDetectingFilterVisitor
import org.locationtech.geomesa.utils.geohash.GeohashUtils._
import org.locationtech.geomesa.utils.geotools.GeometryUtils
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter._
import org.opengis.filter.expression.{Expression, PropertyName}
import org.opengis.filter.spatial._
import org.opengis.filter.temporal.{After, Before, During, TEquals}
import org.opengis.temporal.Period

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}

object FilterHelper {

  import org.locationtech.geomesa.utils.geotools.GeometryUtils.distanceDegrees
  import org.locationtech.geomesa.utils.geotools.WholeWorldPolygon

  private val SafeGeomString = "gm-safe"

  // helper shim to let other classes avoid importing FilterHelper.logger
  object FilterHelperLogger extends LazyLogging {
    private [FilterHelper] def log = logger
  }

  /**
    * Creates a new filter with valid bounds and attribute
    *
    * @param op spatial op
    * @param sft simple feature type
    * @return valid op
    */
  def visitBinarySpatialOp(op: BinarySpatialOperator, sft: SimpleFeatureType, factory: FilterFactory2): Filter = {
    val prop = org.locationtech.geomesa.filter.checkOrderUnsafe(op.getExpression1, op.getExpression2)
    val geom = prop.literal.evaluate(null, classOf[Geometry])
    if (geom.getUserData == SafeGeomString) {
      op // we've already visited this geom once
    } else {
      // check for null or empty attribute and replace with default geometry name
      val attribute = Option(prop.name).filterNot(_.isEmpty).orElse(Option(sft).map(_.getGeomField)).orNull
      // copy the geometry so we don't modify the original
      val geomCopy = geom.getFactory.createGeometry(geom)
      // trim to world boundaries
      val trimmedGeom = geomCopy.intersection(WholeWorldPolygon)
      if (trimmedGeom.isEmpty) {
        Filter.EXCLUDE
      } else {
        // add waypoints if needed so that IDL is handled correctly
        val geomWithWayPoints = if (op.isInstanceOf[BBOX]) { addWayPointsToBBOX(trimmedGeom) } else { trimmedGeom }
        val safeGeometries = flattenGeometry(tryGetIdlSafeGeom(geomWithWayPoints))
        // mark it as being visited
        safeGeometries.foreach(_.setUserData(SafeGeomString))
        val args: Array[Any] = op match {
          case dwithin: DWithin => Array(dwithin.getDistance, dwithin.getDistanceUnits)
          case _ => null
        }
        orFilters(safeGeometries.map(recreateFilter(op, attribute, _, prop.flipped, factory, args)))(factory)
      }
    }
  }

  private def tryGetIdlSafeGeom(geom: Geometry): Geometry = getInternationalDateLineSafeGeometry(geom) match {
    case Success(g) => g
    case Failure(e) => FilterHelperLogger.log.warn(s"Error splitting geometry on IDL for $geom", e); geom
  }

  private def recreateFilter(op: BinarySpatialOperator,
                             property: String,
                             geom: Geometry,
                             flipped: Boolean,
                             factory: FilterFactory2,
                             args: Array[Any]): Filter = {
    val (e1, e2) = if (flipped) {
      (factory.literal(geom), factory.property(property))
    } else {
      (factory.property(property), factory.literal(geom))
    }
    op match {
      case _: Within     => factory.within(e1, e2)
      case _: Intersects => factory.intersects(e1, e2)
      case _: Overlaps   => factory.overlaps(e1, e2)
      case _: DWithin    => factory.dwithin(e1, e2, args(0).asInstanceOf[Double], args(1).asInstanceOf[String])
      // use the direct constructor so that we preserve our geom user data
      case _: BBOX       => new BBOXImpl(e1, e2)
      case _: Contains   => factory.contains(e1, e2)
    }
  }

  def isFilterWholeWorld(f: Filter): Boolean = f match {
      case op: BBOX       => isOperationGeomWholeWorld(op)
      case op: Intersects => isOperationGeomWholeWorld(op)
      case op: Overlaps   => isOperationGeomWholeWorld(op)
      case op: Within     => isOperationGeomWholeWorld(op, SpatialOpOrder.PropertyFirst)
      case op: Contains   => isOperationGeomWholeWorld(op, SpatialOpOrder.LiteralFirst)
      case _ => false
    }

  private def isOperationGeomWholeWorld[Op <: BinarySpatialOperator]
      (op: Op, order: SpatialOpOrder.SpatialOpOrder = SpatialOpOrder.AnyOrder): Boolean = {
    val prop = checkOrder(op.getExpression1, op.getExpression2)
    // validate that property and literal are in the specified order
    prop.exists { p =>
      val ordered = order match {
        case SpatialOpOrder.AnyOrder      => true
        case SpatialOpOrder.PropertyFirst => !p.flipped
        case SpatialOpOrder.LiteralFirst  => p.flipped
      }
      ordered && Option(p.literal.evaluate(null, classOf[Geometry])).exists(isWholeWorld)
    }
  }

  def isWholeWorld[G <: Geometry](g: G): Boolean = g != null && g.union.covers(WholeWorldPolygon)

  def addWayPointsToBBOX(g: Geometry): Geometry = {
    val geomArray = g.getCoordinates
    val correctedGeom = GeometryUtils.addWayPoints(geomArray).toArray
    if (geomArray.length == correctedGeom.length) { g } else { g.getFactory.createPolygon(correctedGeom) }
  }

  /**
    * Extracts geometries from a filter into a sequence of OR'd geometries
    *
    * @param filter filter to evaluate
    * @param attribute attribute to consider
    * @param intersect intersect AND'd geometries or return them all
    *                  note if not intersected, 'and/or' distinction will be lost
    * @return geometry bounds from spatial filters
    */
  def extractGeometries(filter: Filter, attribute: String, intersect: Boolean = true): FilterValues[Geometry] =
    extractUnclippedGeometries(filter, attribute, intersect).map(_.intersection(WholeWorldPolygon))

  /**
    * Extract geometries from a filter without validating boundaries.
    *
    * @param filter filter to evaluate
    * @param attribute attribute to consider
    * @param intersect intersect AND'd geometries or return them all
    * @return geometry bounds from spatial filters
    */
  private def extractUnclippedGeometries(filter: Filter, attribute: String, intersect: Boolean): FilterValues[Geometry] = {
    filter match {
      case o: Or  =>
        val all = o.getChildren.map(extractUnclippedGeometries(_, attribute, intersect))
        val join = FilterValues.or[Geometry]((l, r) => l ++ r) _
        all.reduceLeftOption[FilterValues[Geometry]](join).getOrElse(FilterValues.empty)

      case a: And =>
        val all = a.getChildren.map(extractUnclippedGeometries(_, attribute, intersect)).filter(_.nonEmpty)
        if (intersect) {
          val intersect = FilterValues.and[Geometry]((l, r) => Option(l.intersection(r)).filterNot(_.isEmpty)) _
          all.reduceLeftOption[FilterValues[Geometry]](intersect).getOrElse(FilterValues.empty)
        } else {
          FilterValues(all.flatMap(_.values))
        }

      // Note: although not technically required, all known spatial predicates are also binary spatial operators
      case f: BinarySpatialOperator if isSpatialFilter(f) =>
        val geometry = for {
          prop <- checkOrder(f.getExpression1, f.getExpression2)
          if prop.name == null || prop.name == attribute
          geom <- Option(prop.literal.evaluate(null, classOf[Geometry]))
        } yield {
          val buffered = filter match {
            case f: DWithin => geom.buffer(distanceDegrees(geom, f.getDistance * metersMultiplier(f.getDistanceUnits))._2)
            case _: BBOX    => addWayPointsToBBOX(geom.getFactory.createGeometry(geom).intersection(WholeWorldPolygon))
            case _          => geom
          }
          tryGetIdlSafeGeom(buffered)
        }
        FilterValues(geometry.map(flattenGeometry).getOrElse(Seq.empty))

      case _ => FilterValues.empty
    }
  }

  def metersMultiplier(units: String): Double = {
    if (units == null) { 1d } else {
      units.trim.toLowerCase(Locale.US) match {
        case "meters"         => 1d
        case "kilometers"     => 1000d
        case "feet"           => 0.3048
        case "statute miles"  => 1609.347
        case "nautical miles" => 1852d
        case _                => 1d // not part of ECQL spec...
      }
    }
  }

  private def flattenGeometry(geometry: Geometry): Seq[Geometry] = geometry match {
    case g: GeometryCollection => Seq.tabulate(g.getNumGeometries)(g.getGeometryN).flatMap(flattenGeometry)
    case _ => Seq(geometry)
  }

  /**
    * Extracts intervals from a filter. Intervals will be merged where possible - the resulting sequence
    * is considered to be a union (i.e. OR)
    *
    * @param filter filter to evaluate
    * @param attribute attribute to consider
    * @param intersect intersect extracted values together, or return them all
    *                  note if not intersected, 'and/or' distinction will be lost
    * @return a sequence of intervals, if any. disjoint intervals will result in Seq((null, null))
    */
  def extractIntervals(filter: Filter,
                       attribute: String,
                       intersect: Boolean = true,
                       handleExclusiveBounds: Boolean = false): FilterValues[Bounds[ZonedDateTime]] = {
    extractAttributeBounds(filter, attribute, classOf[Date]).map { bounds =>
      var lower, upper: Bound[ZonedDateTime] = null
      if (!handleExclusiveBounds || bounds.lower.value.isEmpty || bounds.upper.value.isEmpty ||
          (bounds.lower.inclusive && bounds.upper.inclusive)) {
        lower = createDateTime(bounds.lower, roundSecondsUp, handleExclusiveBounds)
        upper = createDateTime(bounds.upper, roundSecondsDown, handleExclusiveBounds)
      } else {
        // check for extremely narrow filters where our rounding makes the result out-of-order
        // note: both upper and lower are known to be defined based on hitting this else branch
        val margin = if (bounds.lower.inclusive || bounds.upper.inclusive) { 1000 } else { 2000 }
        val round = bounds.upper.value.get.getTime - bounds.lower.value.get.getTime > margin
        lower = createDateTime(bounds.lower, roundSecondsUp, round)
        upper = createDateTime(bounds.upper, roundSecondsDown, round)
      }
      Bounds(lower, upper)
    }
  }

  private def createDateTime(bound: Bound[Date],
                             round: (ZonedDateTime) => ZonedDateTime,
                             roundExclusive: Boolean): Bound[ZonedDateTime] = {
    if (bound.value.isEmpty) { Bound.unbounded } else {
      val dt = bound.value.map(d => ZonedDateTime.ofInstant(d.toInstant, ZoneOffset.UTC))
      if (roundExclusive && !bound.inclusive) {
        Bound(dt.map(round), inclusive = true)
      } else {
        Bound(dt, bound.inclusive)
      }
    }
  }

  private def roundSecondsUp(dt: ZonedDateTime): ZonedDateTime = dt.plusSeconds(1).withNano(0)

  private def roundSecondsDown(dt: ZonedDateTime): ZonedDateTime = {
    val nanos = dt.getNano
    if (nanos == 0) { dt.minusSeconds(1) } else { dt.withNano(0) }
  }

  /**
    * Extracts bounds from filters that pertain to a given attribute. Bounds will be merged where
    * possible.
    *
    * @param filter filter to evaluate
    * @param attribute attribute name to consider
    * @param binding attribute type
    * @return a sequence of bounds, if any
    */
  def extractAttributeBounds[T](filter: Filter, attribute: String, binding: Class[T]): FilterValues[Bounds[T]] = {
    filter match {
      case o: Or =>
        val all = o.getChildren.flatMap { f =>
          val child = extractAttributeBounds(f, attribute, binding)
          if (child.isEmpty) { Seq.empty } else { Seq(child) }
        }
        val union = FilterValues.or[Bounds[T]](Bounds.union[T]) _
        all.reduceLeftOption[FilterValues[Bounds[T]]](union).getOrElse(FilterValues.empty)

      case a: And =>
        val all = a.getChildren.flatMap { f =>
          val child = extractAttributeBounds(f, attribute, binding)
          if (child.isEmpty) { Seq.empty } else { Seq(child) }
        }
        val intersection = FilterValues.and[Bounds[T]](Bounds.intersection[T]) _
        all.reduceLeftOption[FilterValues[Bounds[T]]](intersection).getOrElse(FilterValues.empty)

      case f: PropertyIsEqualTo =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              val bound = Bound(Some(lit), inclusive = true)
              FilterValues(Seq(Bounds(bound, bound)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = true, binding)
        }.getOrElse(FilterValues.empty)

      case f: PropertyIsBetween =>
        try {
          val prop = f.getExpression.asInstanceOf[PropertyName].getPropertyName
          if (prop != attribute) { FilterValues.empty } else {
            // note that between is inclusive
            val lower = Bound(Option(f.getLowerBoundary.evaluate(null, binding)), inclusive = true)
            val upper = Bound(Option(f.getUpperBoundary.evaluate(null, binding)), inclusive = true)
            FilterValues(Seq(Bounds(lower, upper)))
          }
        } catch {
          case e: Exception =>
            FilterHelperLogger.log.warn(s"Unable to extract bounds from filter '${filterToString(f)}'", e)
            FilterValues.empty
        }

      case f: During if classOf[Date].isAssignableFrom(binding) =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, classOf[Period])).map { p =>
              // note that during is exclusive
              val lower = Bound(Option(p.getBeginning.getPosition.getDate.asInstanceOf[T]), inclusive = false)
              val upper = Bound(Option(p.getEnding.getPosition.getDate.asInstanceOf[T]), inclusive = false)
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = false, binding)
        }.getOrElse(FilterValues.empty)

      case f: PropertyIsGreaterThan =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              val bound = Bound(Some(lit), inclusive = false)
              val (lower, upper) = if (e.flipped) { (Bound.unbounded[T], bound) } else { (bound, Bound.unbounded[T]) }
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = false, binding)
        }.getOrElse(FilterValues.empty)

      case f: PropertyIsGreaterThanOrEqualTo =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              val bound = Bound(Some(lit), inclusive = true)
              val (lower, upper) = if (e.flipped) { (Bound.unbounded[T], bound) } else { (bound, Bound.unbounded[T]) }
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = true, binding)
        }.getOrElse(FilterValues.empty)

      case f: PropertyIsLessThan =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              val bound = Bound(Some(lit), inclusive = false)
              val (lower, upper) = if (e.flipped) { (bound, Bound.unbounded[T]) } else { (Bound.unbounded[T], bound) }
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = false, binding)
        }.getOrElse(FilterValues.empty)

      case f: PropertyIsLessThanOrEqualTo =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              val bound = Bound(Some(lit), inclusive = true)
              val (lower, upper) = if (e.flipped) { (bound, Bound.unbounded[T]) } else { (Bound.unbounded[T], bound) }
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = true, binding)
        }.getOrElse(FilterValues.empty)

      case f: Before =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              // note that before is exclusive
              val bound = Bound(Some(lit), inclusive = false)
              val (lower, upper) = if (e.flipped) { (bound, Bound.unbounded[T]) } else { (Bound.unbounded[T], bound) }
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = false, binding)
        }.getOrElse(FilterValues.empty)

      case f: After =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap {
          case e: PropertyLiteral =>
            Option(e.literal.evaluate(null, binding)).map { lit =>
              // note that after is exclusive
              val bound = Bound(Some(lit), inclusive = false)
              val (lower, upper) = if (e.flipped) { (Bound.unbounded[T], bound) } else { (bound, Bound.unbounded[T]) }
              FilterValues(Seq(Bounds(lower, upper)))
            }

          case e: FunctionLiteral => extractFunctionBounds(e, inclusive = false, binding)
        }.getOrElse(FilterValues.empty)

      case f: PropertyIsLike if binding == classOf[String] =>
        try {
          val prop = f.getExpression.asInstanceOf[PropertyName].getPropertyName
          if (prop != attribute) { FilterValues.empty } else {
            // Remove the trailing wildcard and create a range prefix
            val literal = f.getLiteral
            val lower = if (literal.endsWith(MULTICHAR_WILDCARD)) {
              literal.substring(0, literal.length - MULTICHAR_WILDCARD.length)
            } else {
              literal
            }
            val upper = Bound(Some(lower + WILDCARD_SUFFIX), inclusive = true).asInstanceOf[Bound[T]]
            FilterValues(Seq(Bounds(Bound(Some(lower.asInstanceOf[T]), inclusive = true), upper)))
          }
        } catch {
          case e: Exception =>
            FilterHelperLogger.log.warn(s"Unable to extract bounds from filter '${filterToString(f)}'", e)
            FilterValues.empty
        }

      case f: Not if f.getFilter.isInstanceOf[PropertyIsNull] =>
        try {
          val isNull = f.getFilter.asInstanceOf[PropertyIsNull]
          val prop = isNull.getExpression.asInstanceOf[PropertyName].getPropertyName
          if (prop != attribute) { FilterValues.empty } else {
            FilterValues(Seq(Bounds.everything[T]))
          }
        } catch {
          case e: Exception =>
            FilterHelperLogger.log.warn(s"Unable to extract bounds from filter '${filterToString(f)}'", e)
            FilterValues.empty
        }

      case f: Not =>
        // we extract the sub-filter bounds, then invert them
        val inverted = extractAttributeBounds(f.getFilter, attribute, binding)
        if (inverted.isEmpty) {
          inverted
        } else if (inverted.disjoint) {
          FilterValues(Seq(Bounds.everything[T])) // equivalent to not null
        } else if (!inverted.precise) {
          FilterHelperLogger.log.warn(s"Falling back to full table scan for inverted query: '${filterToString(f)}'")
          FilterValues(Seq(Bounds.everything[T]), precise = false)
        } else {
          // NOT(A OR B) turns into NOT(A) AND NOT(B)
          val uninverted = inverted.values.map { bounds =>
            // NOT the single bound
            val not = bounds.bounds match {
              case (None, None) => Seq.empty
              case (Some(lo), None) => Seq(Bounds(Bound.unbounded, Bound(Some(lo), !bounds.lower.inclusive)))
              case (None, Some(hi)) => Seq(Bounds(Bound(Some(hi), !bounds.upper.inclusive), Bound.unbounded))
              case (Some(lo), Some(hi)) => Seq(
                  Bounds(Bound.unbounded, Bound(Some(lo), !bounds.lower.inclusive)),
                  Bounds(Bound(Some(hi), !bounds.upper.inclusive), Bound.unbounded)
                )
            }
            FilterValues(not)
          }
          // AND together
          val intersect = FilterValues.and[Bounds[T]](Bounds.intersection[T]) _
          uninverted.reduceLeft[FilterValues[Bounds[T]]](intersect)
        }

      case f: TEquals =>
        checkOrder(f.getExpression1, f.getExpression2).filter(_.name == attribute).flatMap { prop =>
          Option(prop.literal.evaluate(null, binding)).map { lit =>
            val bound = Bound(Some(lit), inclusive = true)
            FilterValues(Seq(Bounds(bound, bound)))
          }
        }.getOrElse(FilterValues.empty)

      case _ => FilterValues.empty
    }
  }

  private def extractFunctionBounds[T](function: FunctionLiteral,
                                       inclusive: Boolean,
                                       binding: Class[T]): Option[FilterValues[Bounds[T]]] = {
    // TODO GEOMESA-1990 extract some meaningful bounds from the function
    Some(FilterValues(Seq(Bounds.everything[T]), precise = false))
  }

  def propertyNames(filter: Filter, sft: SimpleFeatureType): Seq[String] =
    DataUtilities.attributeNames(filter, sft).toSeq.distinct.sorted

  def propertyNames(expression: Expression, sft: SimpleFeatureType): Seq[String] =
    DataUtilities.attributeNames(expression, sft).toSeq.distinct.sorted

  def hasIdFilter(filter: Filter): Boolean =
    filter.accept(new IdDetectingFilterVisitor, false).asInstanceOf[Boolean]

  def filterListAsAnd(filters: Seq[Filter]): Option[Filter] = andOption(filters)

  /**
    * Simplifies filters to make them easier to process.
    *
    * Current simplifications:
    *
    *   1) Extracts out common parts in an OR clause to simplify further processing.
    *
    *      Example: OR(AND(1, 2), AND(1, 3), AND(1, 4)) -> AND(1, OR(2, 3, 4))
    *
    *   2) N/A - add more simplifications here as needed
    *
    * @param filter filter
    * @return
    */
  def simplify(filter: Filter): Filter = {
    def deduplicateOrs(f: Filter): Filter = f match {
      case and: And => ff.and(and.getChildren.map(deduplicateOrs))

      case or: Or =>
        // OR(AND(1,2,3), AND(1,2,4)) -> Seq(Seq(1,2,3), Seq(1,2,4))
        val decomposed = or.getChildren.map(decomposeAnd)
        val clauses = decomposed.head // Seq(1,2,3)
        val duplicates = clauses.filter(c => decomposed.tail.forall(_.contains(c))) // Seq(1,2)
        if (duplicates.isEmpty) { or } else {
          val deduplicated = orOption(decomposed.flatMap(d => andOption(d.filterNot(duplicates.contains))))
          andFilters(deduplicated.toSeq ++ duplicates)
        }

      case _ => f
    }
    // TODO GEOMESA-1533 simplify ANDs of ORs for DNF
    flatten(deduplicateOrs(flatten(filter)))
  }

  /**
    * Flattens nested ands and ors.
    *
    * Example: AND(1, AND(2, 3)) -> AND(1, 2, 3)
    *
    * @param filter filter
    * @return
    */
  def flatten(filter: Filter): Filter = {
    filter match {
      case and: And  => ff.and(flattenAnd(and.getChildren))
      case or: Or    => ff.or(flattenOr(or.getChildren))
      case f: Filter => f
    }
  }

  private [filter] def flattenAnd(filters: Seq[Filter]): ListBuffer[Filter] = {
    val remaining = ListBuffer.empty[Filter] ++ filters
    val result = ListBuffer.empty[Filter]
    do {
      remaining.remove(0) match {
        case f: And => remaining.appendAll(f.getChildren)
        case f      => result.append(flatten(f))
      }
    } while (remaining.nonEmpty)
    result
  }

  private [filter] def flattenOr(filters: Seq[Filter]): ListBuffer[Filter] = {
    val remaining = ListBuffer.empty[Filter] ++ filters
    val result = ListBuffer.empty[Filter]
    do {
      remaining.remove(0) match {
        case f: Or => remaining.appendAll(f.getChildren)
        case f     => result.append(flatten(f))
      }
    } while (remaining.nonEmpty)
    result
  }

  private object SpatialOpOrder extends Enumeration {
    type SpatialOpOrder = Value
    val PropertyFirst, LiteralFirst, AnyOrder = Value
  }
}
