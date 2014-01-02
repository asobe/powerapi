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
import scala.collection.JavaConversions
import com.typesafe.config.Config

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.library.PowerAPI
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator
import fr.inria.powerapi.processor.aggregator.device.DeviceAggregator
import fr.inria.powerapi.processor.aggregator.process.ProcessAggregator
import fr.inria.powerapi.reporter.console.ConsoleReporter
import fr.inria.powerapi.reporter.file.FileReporter
import fr.inria.powerapi.reporter.gnuplot.GnuplotReporter
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter
import fr.inria.powerapi.reporter.virtio.VirtioReporter

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
  override lazy val pids    = Processes.allPIDs
  override lazy val devices = Processes.allDevs
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
  var allDevs = List[String]("cpu")

  /**
   * Start the CPU monitoring
   */
  def start(pids: List[Int], apps: String, devs: List[String], agg: String, out: String, freq: Int, time: Int, appscont: Int) {
    // Retrieve the PIDs of the APPs given in parameters
    val PSFormat = """^\s*(\d+).*""".r
    val appsPID = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", apps, "ho", "pid")).getInputStream).lines().toList.map({
      pid =>
        pid match {
          case PSFormat(id) => id.toInt
          case _ => -1
        }
    })
    allPIDs = (pids ++ appsPID).filter(elt => elt != -1).distinct.sortWith(_.compareTo(_) < 0)
    allDevs = devs.distinct.sortWith(_.compareTo(_) < 0)
    if (appscont == 1) {
      customUpdate(allPIDs, agg, out, freq, time, apps)
    }
    else {
    // Monitor all process if no PID or APP is given in parameters
    if (allPIDs.isEmpty)
      all(agg, out, freq, time)
    // else monitor the specified processes in parameters
    else
      custom(allPIDs, agg, out, freq, time)
    }
    // Create the gnuplot script to generate the graph
    if (out == "gnuplot")
      if (allPIDs.nonEmpty)
        GnuplotScript.create(allPIDs.map(_.toString), filePath)
      else
        GnuplotScript.create(allDevs, filePath)
  }
  
  /**
   * CPU monitoring wich hardly specifying the monitored process.
   */
  def custom(pids: List[Int], agg: String, out: String, freq: Int, time: Int) {
    pids.foreach(pid => 
      PowerAPI.startMonitoring(
        process = Process(pid),
        duration = freq.millis
      )
    )
    PowerAPI.startMonitoring(
      processor = getProcessor(agg),
      listener = getReporter(out)
    )
    Thread.sleep((time.minute).toMillis)
    PowerAPI.stopMonitoring(
      processor = getProcessor(agg),
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
 * CPU monitoring for specific processes that is updated regularly.
 * Can be used for load scripts with child processes.
**/
  def customUpdate(pids: List[Int],agg:String,out:String,freq:Int,time:Int, apps:String) {
def getPids = {
      val PSFormat = """^\s*(\d+).*""".r
      val pids = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps","-C",apps,"ho","pid")).getInputStream).lines().toList.map({
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
    def updateMonitoredPids() {
val currentPids = scala.collection.mutable.Set[Int](getPids: _*)

      val oldPids = pids -- currentPids
      oldPids.foreach(pid => PowerAPI.stopMonitoring(process = Process(pid), duration = dur))
      pids --= oldPids

      val newPids = currentPids -- pids
      newPids.foreach(pid => PowerAPI.startMonitoring(process = Process(pid), duration = dur))
      pids ++= newPids
      //println("updated the pids:"+pids.toString)
       allPIDs = pids.toList
    }
            PowerAPI.startMonitoring(
      processor = getProcessor(agg),
      listener  = getReporter(out)
    )
    val timer = new Timer
    timer.scheduleAtFixedRate(new TimerTask() {
      def run() {
        updateMonitoredPids
      }
    }, Duration.Zero.toMillis, (250.milliseconds).toMillis)
    Thread.sleep((time.minutes).toMillis)
    timer.cancel
    PowerAPI.stopMonitoring(
      processor = getProcessor(agg),
      listener  = getReporter(out)
    )
  }
 
  /**
   * Intensive process CPU monitoring in periodically scanning all current processes.
   */
  def all(agg: String, out: String, freq: Int, time: Int) {
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
      processor = getProcessor(agg),
      listener  = getReporter(out)
    )
    val timer = new Timer

    timer.scheduleAtFixedRate(new TimerTask() {
      def run() {
        udpateMonitoredPids
      }
    }, Duration.Zero.toMillis, (250.milliseconds).toMillis)

    Thread.sleep((time.minutes).toMillis)

    timer.cancel
    PowerAPI.stopMonitoring(
      processor = getProcessor(agg),
      listener  = getReporter(out)
    )
  }
  
  def getProcessor(processor: String) = {
    processor match {
      case "device" => classOf[DeviceAggregator]
      case "process" => classOf[ProcessAggregator]
      case _ => classOf[TimestampAggregator]
    }
  }
  
  def getReporter(reporter: String) = {
    reporter match {
      case "console" => classOf[ConsoleReporter]
      case "file" => classOf[ExtendFileReporter]
      case "gnuplot" => classOf[ExtendGnuplotReporter]
      case "chart" => classOf[JFreeChartReporter]
      case "virtio" => classOf[ExtendVirtioReporter]
      case _ => classOf[JFreeChartReporter]
    }
  }
}
