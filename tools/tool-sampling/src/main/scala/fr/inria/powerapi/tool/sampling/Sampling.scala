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
package fr.inria.powerapi.tool.sampling

import fr.inria.powerapi.core.Reporter
import fr.inria.powerapi.library.{ PAPI, PIDS }
import fr.inria.powerapi.sensor.powerspy.SensorPowerspy
import fr.inria.powerapi.formula.powerspy.FormulaPowerspy
import fr.inria.powerapi.sensor.libpfm.{ LibpfmSensorMessage, SensorLibpfm }
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp
import fr.inria.powerapi.reporter.file.FileReporter

import akka.actor.{ Actor, Props }

import scala.concurrent.duration.DurationInt
import scala.sys.process._

import scalax.file.Path
import scalax.io.{ Resource, SeekableByteChannel }
import scalax.io.managed.SeekableByteChannelResource

trait Configuration extends fr.inria.powerapi.core.Configuration {
  /** Core numbers (virtual incl.) */
  lazy val cores = load { _.getInt("powerapi.tool.sampling.cores") }(4)
  /** Number of required messages per step. */
  lazy val nbMessages = load { _.getInt("powerapi.tool.sampling.step.messages") }(15)
  /** Path used to store the files created during the sampling. */
  lazy val samplingPath = load { _.getString("powerapi.tool.sampling.path") }("samples/")
  /**
   * Scaling frequencies information, giving information about the available frequencies for each core.
   * This information is typically given by the cpufrequtils utils.
   *
   * @see http://www.kernel.org/pub/linux/utils/kernel/cpufreq/cpufreq-info.html
   */
  lazy val scalingFreqPath = load { _.getString("powerapi.tool.sampling.scaling-available-frequencies") }("/sys/devices/system/cpu/cpu%?/cpufreq/scaling_available_frequencies")
  /** Default values for the output files */
  lazy val outBasePathLibpfm = "output-libpfm-"
  lazy val outPathPowerspy = "output-powerspy.dat"
  lazy val separator = "======="
}

class PowerspyReporter extends FileReporter with Configuration {
  override lazy val filePath = outPathPowerspy
}

/**
 * It is a specific component to handle directly the messages produce by the LibpfmSensor.
 * We just want to write the counter values into a file.
 */
class LibpfmListener extends Actor with Configuration {
  // Store all the streams to improve the speed processing.
  val resources = scala.collection.mutable.HashMap[String, SeekableByteChannelResource[SeekableByteChannel]]()
  
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmSensorMessage])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[LibpfmSensorMessage])
  }

  case class Line(sensorMessage: LibpfmSensorMessage) {
    override def toString() =
      "timestamp=" + sensorMessage.tick.timestamp + ";" +
      "process=" + sensorMessage.tick.subscription.process + ";" +
      "counter=" + sensorMessage.counter.value + scalax.io.Line.Terminators.NewLine.sep
  }

  def receive() = {
    case sensorMessage: LibpfmSensorMessage => process(sensorMessage)
  }

  def process(sensorMessage: LibpfmSensorMessage) {
    def updateResources(name: String): SeekableByteChannelResource[SeekableByteChannel] = {
      val output = Resource.fromFile(outBasePathLibpfm + name + ".dat")
      resources += (name -> output)
      output
    }

    val output = resources.getOrElse(sensorMessage.event.name, updateResources(sensorMessage.event.name))
    output.append(Line(sensorMessage).toString)
  }
}

/** 
 * Be careful, we need the root access to write in sys virtual filesystem, else, we can not control the frequency.
 */
object Sampling extends App with Configuration {
  implicit val codec = scalax.io.Codec.UTF8
  val availableFreqs = scala.collection.mutable.SortedSet[Long]()

  // Get the available frequencies from sys virtual filesystem.
  (for(core <- 0 until cores) yield (scalingFreqPath.replace("%?", core.toString))).foreach(filepath => {
    availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
  })

  Path.fromString("test").deleteRecursively(force = true)

  //for(frequency <- availableFreqs) {
    // Set the default governor with the userspace governor. It allows us to control the frequency.
    Seq("bash", "-c", "echo userspace | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
    // Set the frequency
    Seq("bash", "-c", "echo " + availableFreqs(0) + " | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_setspeed > /dev/null")

    // Get the idle power consumption.
    val powerapi = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorTimestamp
                            with SensorLibpfm
    val libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])

    // Idle power
    powerapi.start(PIDS(), 1.seconds).attachReporter(classOf[PowerspyReporter]).waitFor(nbMessages.seconds)
    Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)

    for(core <- 1 to cores) {
      val seqCmd = Seq("/bin/bash", "./src/main/resources/start.bash", "stress -c " + core + " -t " + nbMessages)
      val process = Process(seqCmd, None, "PATH" -> "/usr/bin")
      val buffer = process.lines
      val ppid = buffer(0).trim.toInt
      val monitoring = powerapi.start(PIDS(ppid), 1.seconds).attachReporter(classOf[PowerspyReporter])
      // Processing power
      Seq("kill", "-SIGCONT", ppid+"").!
      monitoring.waitFor(nbMessages.seconds)

      (Path(".") * "output-libpfm-*.dat").foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
      Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
    }
    
    powerapi.system.stop(libpfmListener)
    powerapi.stop
  //}

  // Reset the governor with the ondemand policy.
  Seq("bash", "-c", "echo ondemand | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!

  System.exit(0)
}