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
package fr.inria.powerapi.exp.middleware

import fr.inria.powerapi.core.{ Configuration, Energy, Process, ProcessedMessage, Reporter, Tick, TickSubscription }
import fr.inria.powerapi.library.{ ALL, PAPI, PIDS }
import fr.inria.powerapi.sensor.libpfm.{ LibpfmUtil, SensorLibpfm }
import fr.inria.powerapi.formula.libpfm.FormulaLibpfm
import fr.inria.powerapi.sensor.powerspy.SensorPowerspy
import fr.inria.powerapi.formula.powerspy.FormulaPowerspy
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter

import scala.concurrent.duration.DurationInt
import scala.sys.process._
import scalax.file.Path
import scalax.file.ImplicitConversions.string2path
import scalax.io.Resource

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

class ExtendedFileReporter extends Reporter {
  case class Line(processedMessage: ProcessedMessage) {
    override def toString() = 
      "timestamp=" + processedMessage.tick.timestamp + ";" +
      "power=" + processedMessage.energy.power + scalax.io.Line.Terminators.NewLine.sep
  }

  override def process(processedMessage: ProcessedMessage) {
    Resource.fromFile("powerapi_" + processedMessage.device.toLowerCase + ".dat").append(Line(processedMessage).toString)
  }
}

object Default {
  def run() = {
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
}

/**
 * Object for the experiments with SPEC CPU 2006.
 */
object SpecCPUExp {
  def run() = {
    implicit val codec = scalax.io.Codec.UTF8
    val benchmarks = Array("calculix", "soplex", "bzip2")
    val path = "/home/colmant/cpu2006"
    val duration = "30"
    val dataPath = "data"
    val nbRuns = 3
    val separator = "====="

    LibpfmUtil.initialize()
    val libpfm = new PAPI with SensorLibpfm with FormulaLibpfm with AggregatorExtendedDevice
    val powerspy = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorExtendedDevice
    // Cleaning phase
    Path.fromString(dataPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    val benchsToKill = (Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "grep _base.amd64-m64-gcc43-nn") #> Seq("bash", "-c", "head -n 1") #> Seq("bash", "-c", "cut -d '/' -f 6") #> Seq("bash", "-c", "cut -d ' ' -f1")).lines

    benchsToKill.foreach(benchmark => Seq("bash", "-c", s"killall -s KILL specperl runspec specinvoke $benchmark &> /dev/null").run)

    for(run <- 1 to nbRuns) {
      benchmarks.foreach(benchmark => {
        val monitoringLibpfm = libpfm.start(ALL(), 1.seconds).attachReporter(classOf[ExtendedFileReporter])
        val monitoringPspy = powerspy.start(PIDS(-1), 1.seconds).attachReporter(classOf[ExtendedFileReporter])

        // Waiting for the synchronization.
        Thread.sleep((20.seconds).toMillis)
        (Path(".") * "*.dat").foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))

        // Launch the benchmark with a bash script (easier way).
        Seq("bash", "./src/main/resources/start_bench.bash", path, benchmark, duration).run

        monitoringLibpfm.waitFor(duration.toInt.seconds)

        // Move files to the right place.
        s"$dataPath/$benchmark/$run".createDirectory(failIfExists=false)
        (Path(".") * "*.dat").foreach(path => {
          val name = path.name
          val target: Path = s"$dataPath/$benchmark/$run/$name"
          path.moveTo(target=target, replace=true)
        })
      })
    }

    powerspy.stop()
    libpfm.stop()

    LibpfmUtil.terminate()
  }
}

// Object launcher.
object Monitor extends App {
  //Default.run()
  SpecCPUExp.run()
  System.exit(0)
}