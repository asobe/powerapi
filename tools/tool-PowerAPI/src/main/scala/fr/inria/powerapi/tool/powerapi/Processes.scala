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
import java.io.File

import scala.concurrent.duration.{Duration, DurationInt}
import scala.collection.JavaConversions
import scalax.file.Path
import scalax.io.Resource

import com.typesafe.config.Config

import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Reporter
import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.library.PowerAPI
import fr.inria.powerapi.processor.aggregator.device.DeviceAggregator
import fr.inria.powerapi.processor.aggregator.process.ProcessAggregator
import fr.inria.powerapi.reporter.console.ConsoleReporter
import fr.inria.powerapi.reporter.file.FileReporter
import fr.inria.powerapi.reporter.virtio.VirtioReporter
import fr.inria.powerapi.reporter.gnuplot.GnuplotReporter
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter

class ExtendFileReporter extends FileReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Processes.filePath + " as output file")
    Path.fromString(Processes.filePath+".dat").deleteIfExists()
    Resource.fromFile(Processes.filePath+".dat")
  }
}

class ExtendGnuplotReporter extends GnuplotReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Processes.filePath + " as output file")
    Path.fromString(Processes.filePath+".dat").deleteIfExists()
    Resource.fromFile(Processes.filePath+".dat")
  }
  override lazy val pids = Processes.allPIDs
}
  
class ExtendVirtioReporter extends VirtioReporter {
    override lazy val vmsConfiguration = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.vms")))
        yield (item.asInstanceOf[Config].getInt("pid"), item.asInstanceOf[Config].getInt("port"))).toMap
  } (Map[Int, Int]())
}

/**
 * Set of different use cases of energy monitoring.
 *
 * @author abourdon
 */
object Processes {

  var filePath = "powerapi-out"
  var allPIDs = List[Int]()

  /**
   * Start the CPU monitoring
   */
  def start(pids: Array[Int], apps: String, out: String, freq: Int) {
    // Retrieve the PIDs of the APPs given in parameters
    val PSFormat = """^\s*(\d+).*""".r
    val appsPID = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", apps, "ho", "pid")).getInputStream).lines().toList.map({
      pid =>
        pid match {
          case PSFormat(id) => id.toInt
          case _ => -1
        }
    })
    
    allPIDs = (pids ++ appsPID).filter(elt => elt != -1).distinct.sortWith(_.compareTo(_) < 0).toList
    
    // Monitor all process if no PID or APP is given in parameters
    if (allPIDs.isEmpty)
      all(out, freq)
    // else monitor the specified processes in parameters
    else
      custom(allPIDs, out, freq)
      
    // Create the gnuplot script to generate the graph
    if (out == "gnuplot")
      GnuplotScript.create(allPIDs, filePath)
  }
  
  /**
   * CPU monitoring wich hardly specifying the monitored process.
   */
  def custom(pids: List[Int], out: String, freq: Int) {
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
    Thread.sleep((1.minute).toMillis)
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

    Thread.sleep((1.minutes).toMillis)

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
      case "gnuplot" => classOf[ExtendGnuplotReporter]
      case "chart" => classOf[JFreeChartReporter]
      case "virtio" => classOf[VirtioReporter]
      case _ => classOf[JFreeChartReporter]
    }
  }
}
