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
package fr.inria.powerapi.reporter.gnuplot

import scala.collection.JavaConversions
import scalax.io.Resource
import scalax.file.Path

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.Listener
import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.core.Reporter
import fr.inria.powerapi.library.PowerAPI

/**
 * GnuplotReporter's configuration part.
 *
 * @author lhuertas
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  /**
   * The output gnuplot data file path, build from prefix given by user.
   * Temporary file as default.
   */
  lazy val filePath = load(_.getString("powerapi.reporter.gnuplot.prefix") + System.nanoTime())(Path.createTempFile(prefix = "powerapi.reporter-gnuplot", deleteOnExit = false).path)

  /**
   * The process PIDs to monitor
   */
  lazy val pids = load{
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getIntList("powerapi.pids")))
        yield (item.toInt)).toList
  }(List[Int]())
}

/**
 * Listen to AggregatedMessage and display its content into a given gnuplot file.
 *
 * @author lhuertas
 */
class GnuplotReporter extends Reporter with Configuration {
  
  lazy val processesEnergy = new Array[Double](pids.size)
  var lastTimestamp = 0L
  var firstTimestamp = 0L

  def toMultiGnuplot(processedMessage: ProcessedMessage) {
    if (lastTimestamp == 0L || processedMessage.tick.timestamp == lastTimestamp) {
      processesEnergy(pids.indexOf(processedMessage.tick.subscription.process.pid)) = processedMessage.energy.power
    }
    else {
      output.append(
        ((lastTimestamp-firstTimestamp).toDouble/1000.0) + " " + processesEnergy.mkString(" ") +
        scalax.io.Line.Terminators.NewLine.sep
      )
    }
    processesEnergy(pids.indexOf(processedMessage.tick.subscription.process.pid)) = processedMessage.energy.power
    lastTimestamp = processedMessage.tick.timestamp
  }
  
  def toSingleGnuplot(processedMessage: ProcessedMessage) {
    output.append(
      ((processedMessage.tick.timestamp-firstTimestamp).toDouble/1000.0) + " " + processedMessage.energy.power +
      scalax.io.Line.Terminators.NewLine.sep
    )
  }
  
  lazy val output = {
    if (log.isInfoEnabled) log.info("using " + filePath + " as output file")
    Resource.fromFile(filePath)
  }
  
  def process(processedMessage: ProcessedMessage) {
    if (firstTimestamp == 0L) firstTimestamp = processedMessage.tick.timestamp
    
    // if the ProcessAgreggator is using
    if (processedMessage.tick.subscription.process.pid != -1 && pids.nonEmpty)
      toMultiGnuplot(processedMessage)
    // if other aggregator is using
    else
      toSingleGnuplot(processedMessage)
  }

}
