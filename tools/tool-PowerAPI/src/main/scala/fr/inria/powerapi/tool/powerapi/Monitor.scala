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

import fr.inria.powerapi.library.PowerAPI
import scala.collection.JavaConversions
import scalax.file.Path
import scalax.io.Resource
import com.typesafe.config.Config
import scala.concurrent.duration.DurationInt
import scala.sys.process._

import collection.mutable

class ExtendFileReporter extends fr.inria.powerapi.reporter.file.FileReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Monitor.filePath + " as output file")
    Path.fromString(Monitor.filePath+".dat").deleteIfExists()
    Resource.fromFile(Monitor.filePath+".dat")
  }
}

/**
 * Hot fix: configuration file for the api wich used powerspy.
 */
trait PowerspyFileReporterConf extends fr.inria.powerapi.reporter.file.Configuration {
  override lazy val filePath = "powerapi-out-powerspy.dat"
}

class ExtendedPowerspyReporter extends fr.inria.powerapi.reporter.file.FileReporter with PowerspyFileReporterConf

class ExtendGnuplotReporter extends fr.inria.powerapi.reporter.gnuplot.GnuplotReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Monitor.filePath + " as output file")
    Path.fromString(Monitor.filePath+".dat").deleteIfExists()
    Resource.fromFile(Monitor.filePath+".dat")
  }
  override lazy val pids    = Monitor.allPIDs.toList
  override lazy val devices = Monitor.allDevs
}

object Initializer extends fr.inria.powerapi.sensor.libpfm.LibpfmConfiguration {
  var devs = List[String]("cpu")

  def start(cpuSensor:String, cpuFormula:String,
            memSensor:String, memFormula:String,
            diskSensor:String, diskFormula:String,
            aggregator: String): fr.inria.powerapi.library.PAPI = {

    val powerapi = new fr.inria.powerapi.library.PAPI
    
    if(cpuSensor == "sensor-libpfm" || cpuFormula == "formula-libpfm") {
      fr.inria.powerapi.sensor.libpfm.LibpfmUtil.initialize()

      // Special cases for libpfm sensor & formula.
      if(cpuSensor == "sensor-libpfm") {
        // One sensor per event.
        events.distinct.foreach(event => powerapi.configure(new fr.inria.powerapi.sensor.libpfm.SensorLibpfmConfigured(event)))
      }

      if(cpuFormula == "formula-libpfm") {
        powerapi.configure(fr.inria.powerapi.formula.libpfm.FormulaListener)
        powerapi.configure(fr.inria.powerapi.formula.libpfm.FormulaLibpfm)
      }
    }

    else {
      Array(
        cpuSensor match {
          case "cpu-proc"        => fr.inria.powerapi.sensor.cpu.proc.SensorCpuProc
          case "cpu-proc-reg"    => fr.inria.powerapi.sensor.cpu.proc.reg.SensorCpuProcReg
          case "cpu-proc-virtio" => fr.inria.powerapi.sensor.cpu.proc.virtio.SensorCpuProcVirtio
          case "powerspy"    => fr.inria.powerapi.sensor.powerspy.SensorPowerspy
        },
        cpuFormula match {
          case "cpu-max"        => fr.inria.powerapi.formula.cpu.max.FormulaCpuMax
          case "cpu-maxvm"      => fr.inria.powerapi.formula.cpu.maxvm.FormulaCpuMaxVM
          case "cpu-reg"        => fr.inria.powerapi.formula.cpu.reg.FormulaCpuReg
          case "powerspy"   => fr.inria.powerapi.formula.powerspy.FormulaPowerspy
        }
      ).foreach(powerapi.configure(_))
    }

    if (memSensor != "" && memFormula != "") {
      Array(
        memSensor match {
          case "mem-proc"  => fr.inria.powerapi.sensor.mem.proc.SensorMemProc
          case "mem-sigar" => fr.inria.powerapi.sensor.mem.sigar.SensorMemSigar
        },
        memFormula match {
          case "mem-single" => fr.inria.powerapi.formula.mem.single.FormulaMemSingle
        }
      ).foreach(powerapi.configure(_))
      devs +:= "memory"
    }
    
    if (diskSensor != "" && diskFormula != "") {
      Array(
        diskSensor match {
          case "disk-proc" => fr.inria.powerapi.sensor.disk.proc.SensorDiskProc
          case "disk-atop" => fr.inria.powerapi.sensor.disk.atop.SensorDiskAtop   
        },
        diskFormula match {
          case "disk-single" => fr.inria.powerapi.formula.disk.single.FormulaDiskSingle
        }
      ).foreach(powerapi.configure(_))
      devs +:= "disk"
    }

    val agg = aggregator match {
      case "timestamp" => fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp
      case "process" => fr.inria.powerapi.processor.aggregator.process.AggregatorProcess
      case "device" => fr.inria.powerapi.processor.aggregator.device.AggregatorDevice
    }

    powerapi.configure(agg)
    powerapi
  }
}

/**
 * TODO: refactor the tool, a lot of stuffs here have many dependencies. We have to keep it simple.
 * Example: one file for multiple reporters, it's not the better solution.
 */
object Monitor extends App {
  val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  var powerapi: fr.inria.powerapi.library.PAPI = null
  var powerspy: fr.inria.powerapi.library.PAPI = null
  val mainThread = Thread.currentThread()
  @volatile var running = true

  val shutdownThread = scala.sys.ShutdownHookThread {
    println("\nPowerAPI is going to shutdown ...")
    
    if(powerapi != null) {
      powerapi.stop
    }
  
    if (powerspy != null) {
      powerspy.stop
    }
  }

  private def addClasspath(classpath: String) = {
    try {
      val f = new java.io.File(classpath)
      val u = f.toURI()
      val urlClassLoader = ClassLoader.getSystemClassLoader()
      val urlClass = classOf[java.net.URLClassLoader]
      val method = urlClass.getDeclaredMethod("addURL", classOf[java.net.URL])
      method.setAccessible(true)
      method.invoke(urlClassLoader, u.toURL())
    }
    catch {
      case e: Exception => println("There was a problem during the path loading.")
    }
  }

  lazy val ClasspathFormat  = """-classpath\s+(.+)""".r
  lazy val PidsFormat       = """-pid\s+(\d+[,\d]*)""".r
  lazy val VmsFormat        = """-vm\s+(\d+:\d+[,\d+:\d]*)""".r
  lazy val AppsFormat       = """-app\s+(.+[,.]*)""".r
  lazy val AggregatorFormat = """-aggregator\s+(timestamp|device|process)""".r
  lazy val OutputFormat     = """-output\s+(\w+[,\w]*)""".r
  lazy val FileFormat       = """-filename\s+(\w+)""".r
  lazy val FreqFormat       = """-frequency\s+(\d+)""".r
  lazy val TimeFormat       = """-time\s+(\d+)""".r
  lazy val CpuSensorFormat   = """-cpusensor\s+(cpu-proc|cpu-proc-reg|cpu-proc-virtio|sensor-libpfm)""".r
  lazy val CpuFormulaFormat  = """-cpuformula\s+(cpu-max|cpu-maxvm|cpu-reg|formula-libpfm)""".r
  lazy val MemSensorFormat   = """-memsensor\s+(mem-proc|mem-sigar)""".r
  lazy val MemFormulaFormat  = """-memformula\s+(mem-single)""".r
  lazy val DiskSensorFormat  = """-disksensor\s+(disk-proc|disk-atop)""".r
  lazy val DiskFormulaFormat = """-diskformula\s+(disk-single)""".r
  lazy val PowerspyFormat = """-powerspy\s+(1|0)""".r

  val params =
  (for (arg <- args) yield {
    arg match {
      case ClasspathFormat(classpath)     => ("classpath" -> classpath) 
      case PidsFormat(pids)               => ("pids" -> pids)
      case VmsFormat(vm)                  => ("vm" -> vm)
      case AppsFormat(app)                => ("app" -> app)
      case AggregatorFormat(agg)          => ("agg" -> agg)
      case OutputFormat(out)              => ("out" -> out)
      case FileFormat(filePath)           => ("filePath" -> filePath)
      case FreqFormat(freq)               => ("freq" -> freq)
      case TimeFormat(time)               => ("time" -> time)
      case CpuSensorFormat(cpuSensor)     => ("cpuSensor" -> cpuSensor)
      case CpuFormulaFormat(cpuFormula)   => ("cpuFormula" -> cpuFormula)
      case MemSensorFormat(memSensor)     => ("memSensor" -> memSensor)
      case MemFormulaFormat(memFormula)   => ("memFormula" -> memFormula)
      case DiskSensorFormat(diskSensor)   => ("diskSensor" -> diskSensor)
      case DiskFormulaFormat(diskFormula) => ("diskFormula" -> diskFormula)
      case PowerspyFormat(powerspySet)    => ("powerspySet" -> powerspySet)
      case _ => ("none" -> "")
    }
  }).toMap

  val classpath = params.getOrElse("classpath", "") 
  if(classpath != "") addClasspath(classpath)
  
  powerapi = Initializer.start(
    params.getOrElse("cpuSensor", "cpu-proc"), params.getOrElse("cpuFormula", "cpu-max"),
    params.getOrElse("memSensor", ""), params.getOrElse("memFormula", ""),
    params.getOrElse("diskSensor", ""), params.getOrElse("diskFormula", ""),
    params.getOrElse("agg", "timestamp")
  )
  val powerspySet = params.getOrElse("powerspySet", "0").toInt
  
  if (powerspySet == 1) {
    powerspy = Initializer.start("powerspy","powerspy","","","","","device") 
  }

  var pids = params.getOrElse("pids", "").split(",").filter(_ != "").map(_.toInt)
  val apps = params.getOrElse("app", "").split(",").filter(_ != "")
  var reporters = params.getOrElse("out", "").split(",").filter(_ != "")
  val freq = params.getOrElse("freq", "1000").toInt
  val time = params.getOrElse("time", "5").toInt
  val filePath = params.getOrElse("filePath", "powerapi-out")

  val vmConfiguration = scala.collection.mutable.Map[Int, Int]()
  if(params.isDefinedAt("vm")) {
    // Format : PID:port, PID:port ...
    val vmsPidPort = params("vm").split(',')
    val pidsList = scala.collection.mutable.ListBuffer.empty[Int]

    for(vmConf <- vmsPidPort) {
      var pidPortArr = vmConf.split(':')
      vmConfiguration += (pidPortArr(0).toInt -> pidPortArr(1).toInt)
      pidsList += pidPortArr(0).toInt
    }

    pids = pidsList.toArray
  }

  var allPIDs = mutable.ListBuffer.empty[Int]
  
  var monitoring: fr.inria.powerapi.library.Monitoring = if(pids.isEmpty && apps.isEmpty) {
    val allC = fr.inria.powerapi.library.ALL
    allPIDs ++= (for(process <- allC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(freq.millis, allC)
  }

  else if(!pids.isEmpty && apps.isEmpty) {
    val pidsC = fr.inria.powerapi.library.PIDS(pids: _*)
    allPIDs ++= (for(process <- pidsC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(freq.millis, pidsC)
  }

  else if(pids.isEmpty && !apps.isEmpty) {
    val appsC = fr.inria.powerapi.library.APPS(apps: _*)
    allPIDs ++= (for(process <- appsC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(freq.millis, appsC)
  }

  else {
    val pidsC = fr.inria.powerapi.library.PIDS(pids: _*)
    val appsC = fr.inria.powerapi.library.APPS(apps: _*)
    allPIDs ++= (for(process <- pidsC.monitoredProcesses.toArray) yield process.pid)
    allPIDs ++= (for(process <- appsC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(freq.millis, pidsC, appsC)
  }
   
  var monitoringPowerspy: fr.inria.powerapi.library.Monitoring = null;
  if (powerspySet == 1) {
    monitoringPowerspy = powerspy.start(freq.millis, fr.inria.powerapi.library.PIDS(currentPid))
  }

  if(reporters.isEmpty) reporters = Array("chart")

  reporters.foreach(reporter => reporter match {
    case "console"  => monitoring.attachReporter(classOf[fr.inria.powerapi.reporter.console.ConsoleReporter])
    case "file"     => monitoring.attachReporter(classOf[ExtendFileReporter])
    case "gnuplot"  => monitoring.attachReporter(classOf[ExtendGnuplotReporter])
    case "chart"    => monitoring.attachReporter(classOf[fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter])
    case "virtio"   => monitoring.attachReporter(classOf[fr.inria.powerapi.reporter.virtio.VirtioReporter], vmConfiguration.toMap)
    case "thrift"   => monitoring.attachReporter(classOf[fr.inria.powerapi.reporter.thrift.ThriftReporter])
    case _          => monitoring.attachReporter(classOf[fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter])
  })

  if (powerspySet == 1) {
    monitoringPowerspy.attachReporter(classOf[ExtendedPowerspyReporter])
  }

  val start = System.nanoTime
  val end = start + (time.minute).toNanos

  Thread.sleep((time.minute).toMillis)

  val allDevs = Initializer.devs.distinct.sortWith(_.compareTo(_) < 0)
  
  // Create the gnuplot script to generate the graph
  if(running && reporters.contains("gnuplot")) {
    if (allPIDs.nonEmpty) GnuplotScript.create(allPIDs.map(_.toString).toList, filePath)
    else GnuplotScript.create(allDevs, filePath)
  }

  shutdownThread.start()
  shutdownThread.join()
  shutdownThread.remove()
  System.exit(0)
}