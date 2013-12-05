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
package fr.inria.powerapi.tool.powerapi

import java.util.Timer
import java.util.TimerTask

import scala.concurrent.duration.{Duration, DurationInt}
import scalax.file.Path
import scalax.io.Resource

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.library.PowerAPI
import fr.inria.powerapi.processor.aggregator.device.DeviceAggregator
import fr.inria.powerapi.processor.aggregator.process.ProcessAggregator
import fr.inria.powerapi.reporter.console.ConsoleReporter
import fr.inria.powerapi.reporter.file.FileReporter
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter
import fr.inria.powerapi.reporter.virtio.VirtioReporter

class ExtendFileReporter extends FileReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Processes.filePath + " as output file")
    Path.fromString(Processes.filePath).deleteIfExists()
    Resource.fromFile(Processes.filePath)
  }
}

/**
 * Set of different use cases of energy monitoring.
 *
 * @author abourdon
 */
object Processes {

  var filePath = "powerapi-out.dat"

  /**
   * Start the CPU monitoring
   */
  def start(pids: Array[Int], apps: String, out: String, freq: Int) {
    val PSFormat = """^\s*(\d+).*""".r
    val appsPID = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", apps, "ho", "pid")).getInputStream).lines().toList.map({
      pid =>
        pid match {
          case PSFormat(id) => id.toInt
          case _ => -1
        }
    })
    
    val allPIDs = (pids ++ appsPID).filter(elt => elt != -1)
    
    for (pid <- allPIDs) println("pid="+pid)
    
    if (allPIDs.isEmpty)
      all(out, freq)
    else
      custom(allPIDs, out, freq)
  }
  
  /**
   * CPU monitoring wich hardly specifying the monitored process.
   */
  def custom(pids: Array[Int], out: String, freq: Int) {
    pids.foreach(pid => 
      PowerAPI.startMonitoring(
        process = Process(pid),
        duration = freq.millis
      )
    )
    PowerAPI.startMonitoring(
      processor = classOf[ProcessAggregator],
      listener = getReporter(out)
    )
    Thread.sleep((5.minute).toMillis)
    PowerAPI.stopMonitoring(
      processor = classOf[ProcessAggregator],
      listener = getReporter(out)
    )
    pids.foreach(pid => 
      PowerAPI.stopMonitoring(
        process = Process(pid),
        duration = freq.millis
      )
    )
  }
  
  /**
   * Intensive process CPU monitoring in periodically scanning all current processes.
   */
  def all(out: String, freq: Int) {
    def getPids = {
      val PSFormat = """^\s*(\d+).*""".r
      val pids = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-A")).getInputStream).lines().toList.map({
        pid =>
          pid match {
            case PSFormat(id) => id.toInt
            case _ => -1
          }
      })
      pids.filter(elt => elt != -1)
    }

    val pids = scala.collection.mutable.Set[Int]()
    val dur = freq.millis
    def udpateMonitoredPids() {
      val currentPids = scala.collection.mutable.Set[Int](getPids: _*)

      val oldPids = pids -- currentPids
      oldPids.foreach(pid => PowerAPI.stopMonitoring(process = Process(pid), duration = dur))
      pids --= oldPids

      val newPids = currentPids -- pids
      newPids.foreach(pid => PowerAPI.startMonitoring(process = Process(pid), duration = dur))
      pids ++= newPids
    }

    PowerAPI.startMonitoring(
      processor = classOf[DeviceAggregator],
      listener  = getReporter(out)
    )
    val timer = new Timer

    timer.scheduleAtFixedRate(new TimerTask() {
      def run() {
        udpateMonitoredPids
      }
    }, Duration.Zero.toMillis, (250.milliseconds).toMillis)

    Thread.sleep((5.minutes).toMillis)

    timer.cancel
    PowerAPI.stopMonitoring(
      processor = classOf[DeviceAggregator],
      listener  = getReporter(out)
    )
  }
  
  def getReporter(reporter: String) = {
    reporter match {
      case "console" => classOf[ConsoleReporter]
      case "file" => classOf[ExtendFileReporter]
      case "chart" => classOf[JFreeChartReporter]
      case "virtio" => classOf[VirtioReporter]
      case _ => classOf[JFreeChartReporter]
    }
  }
}
