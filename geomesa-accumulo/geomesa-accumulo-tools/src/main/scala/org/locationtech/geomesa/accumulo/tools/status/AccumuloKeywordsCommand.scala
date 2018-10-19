/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.tools.status

import com.beust.jcommander._
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.accumulo.tools.{AccumuloDataStoreCommand, AccumuloDataStoreParams}
import org.locationtech.geomesa.tools.status.{KeywordsCommand, KeywordsParams}

class AccumuloKeywordsCommand extends KeywordsCommand[AccumuloDataStore] with AccumuloDataStoreCommand {
  override val params = new AccumuloKeywordsParams
}

@Parameters(commandDescription = "Add/Remove/List keywords on an existing schema")
class AccumuloKeywordsParams extends AccumuloDataStoreParams with KeywordsParams
