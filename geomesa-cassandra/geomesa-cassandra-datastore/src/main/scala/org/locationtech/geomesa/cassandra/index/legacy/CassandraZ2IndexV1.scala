/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/


package org.locationtech.geomesa.cassandra.index.legacy

import org.locationtech.geomesa.cassandra.index.CassandraZ2Index
import org.locationtech.geomesa.index.index.legacy.Z2LegacyIndexKeySpace
import org.locationtech.geomesa.index.index.z2.Z2IndexKeySpace

case object CassandraZ2IndexV1 extends CassandraZ2Index {

  override val version: Int = 1

  override protected val keySpace: Z2IndexKeySpace = Z2LegacyIndexKeySpace
}
