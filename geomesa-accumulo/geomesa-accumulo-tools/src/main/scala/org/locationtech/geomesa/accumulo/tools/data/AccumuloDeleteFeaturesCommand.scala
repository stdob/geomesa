/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.tools.data

import com.beust.jcommander.Parameters
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.accumulo.tools.{AccumuloDataStoreCommand, AccumuloDataStoreParams}
import org.locationtech.geomesa.tools.data.{DeleteFeaturesCommand, DeleteFeaturesParams}

class AccumuloDeleteFeaturesCommand extends DeleteFeaturesCommand[AccumuloDataStore] with AccumuloDataStoreCommand {
  override val params = new AccumuloDeleteFeaturesParams
}

@Parameters(commandDescription = "Delete features from a table in GeoMesa. Does not delete any tables or schema information.")
class AccumuloDeleteFeaturesParams extends DeleteFeaturesParams with AccumuloDataStoreParams
