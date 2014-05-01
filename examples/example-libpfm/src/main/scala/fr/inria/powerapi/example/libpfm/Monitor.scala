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
package fr.inria.powerapi.example.libpfm

import scala.concurrent.duration.DurationInt
import fr.inria.powerapi.core.{ Configuration, Energy, Process, ProcessedMessage, Tick, TickSubscription }
import fr.inria.powerapi.library.{ ALL, PAPI, PIDS }
import fr.inria.powerapi.sensor.libpfm.{ LibpfmUtil, SensorLibpfm }
import fr.inria.powerapi.formula.libpfm.FormulaLibpfm
import fr.inria.powerapi.sensor.powerspy.SensorPowerspy
import fr.inria.powerapi.formula.powerspy.FormulaPowerspy
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter

/**
 * Part of extended components. It's used to add the idle power into the estimations.
 */
trait PowerIdleConfiguration extends Configuration {
  lazy val avgIdlePower = load {  _.getDouble("powerapi.libpfm.idle-power") } (0.0)
}

case class AggregatedMessage(tick: Tick, device: String, messages: collection.mutable.Set[ProcessedMessage] = collection.mutable.Set[ProcessedMessage]()) 
extends PowerIdleConfiguration with ProcessedMessage {
  override def energy = {
    var energy = messages.foldLeft(0: Double) { (acc, message) => acc + message.energy.power }
    if(device == "cpu") {
      energy += avgIdlePower
    }
    Energy.fromPower(energy)
  }

  def add(message: ProcessedMessage) {
    messages += message
  }
  def +=(message: ProcessedMessage) {
    add(message)
  }
}

class ExtendedDeviceAggregator extends TimestampAggregator {
  def byDevices(implicit timestamp: Long): Iterable[AggregatedMessage] = {
    val base = cache(timestamp)
    val messages = collection.mutable.ArrayBuffer.empty[AggregatedMessage]

    for (byMonitoring <- base.messages.groupBy(_.tick.clockid)) {
      for (byDevice <- byMonitoring._2.groupBy(_.device)) {
        messages += AggregatedMessage(
          tick = Tick(byMonitoring._1, TickSubscription(byMonitoring._1, Process(-1), base.tick.subscription.duration), timestamp),
          device = byDevice._1,
          messages = byDevice._2
        )
      }
    }

    messages
  }

  override def send(implicit timestamp: Long) {
    byDevices foreach publish
  }
}

object AggregatorExtendedDevice extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[ExtendedDeviceAggregator]
}

trait AggregatorExtendedDevice {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorExtendedDevice)
}

object Monitor extends App {
  LibpfmUtil.initialize()
  val libpfm = new PAPI with SensorLibpfm with FormulaLibpfm with AggregatorExtendedDevice
  val powerspy = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorExtendedDevice

  libpfm.start(ALL(), 1.seconds).attachReporter(classOf[JFreeChartReporter])
  powerspy.start(PIDS(-1), 1.seconds).attachReporter(classOf[JFreeChartReporter])
  
  Thread.sleep((10.minutes).toMillis)
  
  powerspy.stop()
  libpfm.stop()
  
  LibpfmUtil.terminate()
}