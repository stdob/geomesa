/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.tools.ingest

import java.io.File

import com.beust.jcommander.Parameters
import org.apache.accumulo.core.client.Connector
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.accumulo.tools.{AccumuloDataStoreCommand, AccumuloDataStoreParams}
import org.locationtech.geomesa.tools.ingest.{IngestCommand, IngestParams}
import org.locationtech.geomesa.utils.classpath.ClassPathUtils

class AccumuloIngestCommand extends IngestCommand[AccumuloDataStore] with AccumuloDataStoreCommand {

  override val params = new AccumuloIngestParams()

  override val libjarsFile: String = "org/locationtech/geomesa/accumulo/tools/ingest-libjars.list"

  override def libjarsPaths: Iterator[() => Seq[File]] = Iterator(
    () => ClassPathUtils.getJarsFromEnvironment("GEOMESA_ACCUMULO_HOME"),
    () => ClassPathUtils.getJarsFromEnvironment("GEOMESA_HOME"),
    () => ClassPathUtils.getJarsFromEnvironment("ACCUMULO_HOME"),
    () => ClassPathUtils.getJarsFromClasspath(classOf[AccumuloDataStore]),
    () => ClassPathUtils.getJarsFromClasspath(classOf[Connector])
  )
}

@Parameters(commandDescription = "Ingest/convert various file formats into GeoMesa")
class AccumuloIngestParams extends IngestParams with AccumuloDataStoreParams
