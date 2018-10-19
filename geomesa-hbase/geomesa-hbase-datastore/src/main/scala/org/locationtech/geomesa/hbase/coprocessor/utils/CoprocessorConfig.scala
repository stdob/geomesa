/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.coprocessor.utils

import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.opengis.feature.simple.SimpleFeature

case class CoprocessorConfig(options: Map[String, String],
                             bytesToFeatures: Array[Byte] => SimpleFeature,
                             reduce: CloseableIterator[SimpleFeature] => CloseableIterator[SimpleFeature] = (i) => i)
