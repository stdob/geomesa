/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.filter.expression

import org.geotools.filter.expression.PropertyAccessor
import org.geotools.util.Converters
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.expression.{Expression, ExpressionVisitor, PropertyName}
import org.xml.sax.helpers.NamespaceSupport

abstract class FastPropertyName(name: String) extends PropertyName with Expression {

  override def getPropertyName: String = name

  override def getNamespaceContext: NamespaceSupport = null

  override def evaluate[T](obj: AnyRef, target: Class[T]): T = Converters.convert(evaluate(obj), target)

  override def accept(visitor: ExpressionVisitor, extraData: AnyRef): AnyRef = visitor.visit(this, extraData)

  override def hashCode(): Int = name.hashCode()

  override def equals(other: Any): Boolean = other match {
    case that: PropertyName => name == that.getPropertyName
    case _ => false
  }

  // required for some ECQL parsing
  override def toString: String = name
}

object FastPropertyName {

  /**
    * PropertyName implementation that looks up the value by index
    *
    * @param name property name
    * @param index property index
    */
  class FastPropertyNameAttribute(name: String, index: Int) extends FastPropertyName(name) {
    override def evaluate(obj: AnyRef): AnyRef = {
      // usually obj is a simple feature, but this is also expected to return descriptors for SimpleFeatureTypes
      try { obj.asInstanceOf[SimpleFeature].getAttribute(index) } catch {
        case _: ClassCastException =>
          obj match {
            case s: SimpleFeatureType => s.getDescriptor(name)
            case _ => null
          }
      }
    }
  }

  /**
    * PropertyName implementation that delegates to a property accessor
    *
    * @param name property name
    * @param accessor property accessor
    */
  class FastPropertyNameAccessor(name: String, accessor: PropertyAccessor) extends FastPropertyName(name) {
    override def evaluate(obj: AnyRef): AnyRef = accessor.get(obj, name, classOf[AnyRef])
  }
}
