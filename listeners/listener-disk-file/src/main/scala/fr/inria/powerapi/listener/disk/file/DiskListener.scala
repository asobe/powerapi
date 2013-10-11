/**
 * Copyright (C) 2012 Inria, University Lille 1.
 *
 * This file is part of PowerAPI.
 *
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: powerapi-user-list@googlegroups.com.
 */
package fr.inria.powerapi.listener.disk.file

import fr.inria.powerapi.core.Listener
import fr.inria.powerapi.formula.disk.api.DiskFormulaMessage
import scalax.file.Path
import scalax.io.Line
import scalax.io.Resource

/**
 * DiskListener's configuration.
 *
 * @author abourdon
 * @contributor Adel Noureddine
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  /**
   * The output file path.
   */
  lazy val filePath = load(_.getString("powerapi.listener.disk-console.out-prefix") + System.nanoTime())(Path.createTempFile(prefix = "powerapi.listener-disk-file", deleteOnExit = false).path)

  /**
   * If result has to be append to an existing file, or simply overrided.
   */
  lazy val append = load(_.getBoolean("powerapi.listener.disk-console.append"))(true)

  /**
   * If this disk listener has to simply write power, or the whole information contained into the DiskFormulaMessage message.
   */
  lazy val justPower = load(_.getBoolean("powerapi.listener.disk-console.just-power"))(false)
}

/**
 * Disk listener displaying received DiskFormulaMessage into a file following properties
 * contained into a configuration file.
 *
 * @author abourdon
 * @contributor Adel Noureddine
 */
class DiskListener extends Listener with Configuration {
  lazy val output = {
    if (log.isInfoEnabled) log.info("using " + filePath + " as output file")
    Resource.fromFile(filePath)
  }

  def process(diskFormulaMessage: DiskFormulaMessage) {
    val toWrite =
      if (justPower) {
        diskFormulaMessage.energy.power.toString
      } else {
        diskFormulaMessage.toString
      }

    if (append) {
      output.append(toWrite + Line.Terminators.NewLine.sep)
    } else {
      output.truncate(0)
      output.write(toWrite)
    }
  }

  def acquire = {
    case diskFormulaMessage: DiskFormulaMessage => process(diskFormulaMessage)
  }

  def messagesToListen = Array(classOf[DiskFormulaMessage])
}