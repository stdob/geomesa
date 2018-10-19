/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.tools.status

import com.beust.jcommander.Parameters
import org.locationtech.geomesa.fs.tools.FsDataStoreCommand
import org.locationtech.geomesa.fs.tools.FsDataStoreCommand.FsParams
import org.locationtech.geomesa.fs.tools.status.FsGetPartitionsCommand.FsGetPartitionsParams
import org.locationtech.geomesa.tools.{Command, RequiredTypeNameParam}

class FsGetPartitionsCommand extends FsDataStoreCommand {

  override val name: String = "get-partitions"
  override val params = new FsGetPartitionsParams

  override def execute(): Unit = withDataStore { ds =>
    import scala.collection.JavaConverters._
    Command.user.info(s"Partitions for type ${params.featureName}:")
    ds.storage(params.featureName).getMetadata.getPartitions.asScala.map(_.name).sorted.foreach(Command.output.info)
  }
}

object FsGetPartitionsCommand {
  @Parameters(commandDescription = "List GeoMesa feature type for a given Fs resource")
  class FsGetPartitionsParams extends FsParams with RequiredTypeNameParam
}
