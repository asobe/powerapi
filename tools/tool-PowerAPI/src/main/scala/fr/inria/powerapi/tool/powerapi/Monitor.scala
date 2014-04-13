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

import collection.mutable

class ExtendFileReporter extends fr.inria.powerapi.reporter.file.FileReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Monitor.filePath + " as output file")
    Path.fromString(Monitor.filePath+".dat").deleteIfExists()
    Resource.fromFile(Monitor.filePath+".dat")
  }
}

class ExtendGnuplotReporter extends fr.inria.powerapi.reporter.gnuplot.GnuplotReporter {
  override lazy val output = {
    if (log.isInfoEnabled) log.info("using " + Monitor.filePath + " as output file")
    Path.fromString(Monitor.filePath+".dat").deleteIfExists()
    Resource.fromFile(Monitor.filePath+".dat")
  }
  override lazy val pids    = Monitor.allPIDs.toList
  override lazy val devices = Monitor.allDevs
}

class ExtendVirtioReporter extends fr.inria.powerapi.reporter.virtio.VirtioReporter {
    override lazy val vmsConfiguration = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.vms")))
        yield (item.asInstanceOf[Config].getInt("pid"), item.asInstanceOf[Config].getInt("port"))).toMap
  } (Map[Int, Int]())
}

object Initializer {
  var devs = List[String]("cpu")
  val powerapi = new fr.inria.powerapi.library.PAPI

  def start(cpuSensor:String, cpuFormula:String,
            memSensor:String, memFormula:String,
            diskSensor:String, diskFormula:String,
            aggregator: String): fr.inria.powerapi.library.PAPI = {
    Array(
      cpuSensor match {
	      case "cpu-proc"     => fr.inria.powerapi.sensor.cpu.proc.SensorCpuProc
	      case "cpu-proc-reg" => fr.inria.powerapi.sensor.cpu.proc.reg.SensorCpuProcReg
        case "cpu-proc-virtio" => fr.inria.powerapi.sensor.cpu.proc.virtio.SensorCpuProcVirtio 	
      },
      cpuFormula match {
	      case "cpu-max"    => fr.inria.powerapi.formula.cpu.max.FormulaCpuMax
	      case "cpu-maxvm"  => fr.inria.powerapi.formula.cpu.maxvm.FormulaCpuMaxVM
	      case "cpu-reg"    => fr.inria.powerapi.formula.cpu.reg.FormulaCpuReg
      }
    ).foreach(powerapi.configure(_))
    
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

object Monitor extends App {
  lazy val PidsFormat       = """-pid\s+(\d+[,\d]*)""".r
  lazy val VmsFormat        = """-vm\s+(\d+:\d+[,\d+:\d]*)""".r
  lazy val AppsFormat       = """-app\s+(.+[,.]*)""".r
  lazy val AggregatorFormat = """-aggregator\s+(timestamp|device|process)""".r
  lazy val OutputFormat     = """-output\s+(\w+[,\w]*)""".r
  lazy val FileFormat       = """-filename\s+(\w+)""".r
  lazy val FreqFormat       = """-frequency\s+(\d+)""".r
  lazy val TimeFormat       = """-time\s+(\d+)""".r
  lazy val CpuSensorFormat   = """-cpusensor\s+(cpu-proc|cpu-proc-reg|cpu-proc-virtio)""".r
  lazy val CpuFormulaFormat  = """-cpuformula\s+(cpu-max|cpu-maxvm|cpu-reg)""".r
  lazy val MemSensorFormat   = """-memsensor\s+(mem-proc|mem-sigar)""".r
  lazy val MemFormulaFormat  = """-memformula\s+(mem-single)""".r
  lazy val DiskSensorFormat  = """-disksensor\s+(disk-proc|disk-atop)""".r
  lazy val DiskFormulaFormat = """-diskformula\s+(disk-single)""".r

    // Write a runtime configuration file for VMs
  def createVMCOnfiguration(vmParameter: String): Array[Int] = {
    lazy val output = {
      Path.fromString("src/main/resources/vm_configuration.conf").deleteIfExists()
      Resource.fromFile("src/main/resources/vm_configuration.conf")
    }
    // Format : PID:port, PID:port ...
    lazy val vmsPidPort = vmParameter.split(',')
    var res = scala.collection.mutable.ListBuffer.empty[String]

    output.append("powerapi {" + scalax.io.Line.Terminators.NewLine.sep)
    output.append("\tvms = [" + scalax.io.Line.Terminators.NewLine.sep)

    for(vmConf <- vmsPidPort) {
      var pidPortArr = vmConf.split(':')
      output.append("\t\t{ pid = " + pidPortArr(0) + ", port = " + pidPortArr(1) + " }" + scalax.io.Line.Terminators.NewLine.sep)
      res += pidPortArr(0)
    }

    output.append("\t]" + scalax.io.Line.Terminators.NewLine.sep)
    output.append("}" + scalax.io.Line.Terminators.NewLine.sep)
    res.map(_.toInt).toArray
  }

  def getReporter(reporter: String) = {
    reporter match {
      case "console" => classOf[fr.inria.powerapi.reporter.console.ConsoleReporter]
      case "file" => classOf[ExtendFileReporter]
      case "gnuplot" => classOf[ExtendGnuplotReporter]
      case "chart" => classOf[fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter]
      case "virtio" => classOf[ExtendVirtioReporter]
      case "thrift" => classOf[fr.inria.powerapi.reporter.thrift.ThriftReporter]
      case _ => classOf[fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter]
    }
  }
  
  val params =
  (for (arg <- args) yield {
    arg match {
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
      case _ => ("none" -> "")
    }
  }).toMap
  
  val powerapi = Initializer.start(
    params.getOrElse("cpuSensor", "cpu-proc"), params.getOrElse("cpuFormula", "cpu-max"),
    params.getOrElse("memSensor", ""), params.getOrElse("memFormula", ""),
    params.getOrElse("diskSensor", ""), params.getOrElse("diskFormula", ""),
    params.getOrElse("agg", "timestamp")
  )

  var pids = params.getOrElse("pids", "").split(",").filter(_ != "").map(_.toInt)
  val apps = params.getOrElse("app", "").split(",").filter(_ != "")
  var reporters = params.getOrElse("out", "").split(",").filter(_ != "")
  val freq = params.getOrElse("freq", "1000").toInt
  val time = params.getOrElse("time", "5").toInt
  val filePath = params.getOrElse("filePath", "powerapi-out")

  if(params.isDefinedAt("vm")) {
    pids = createVMCOnfiguration(params("vm"))
  }

  var allPIDs = mutable.ListBuffer.empty[Int]
  
  var monitoring: fr.inria.powerapi.library.Monitoring = if(pids.isEmpty && apps.isEmpty) {
    val allC = fr.inria.powerapi.library.ALL()
    allPIDs ++= (for(process <- allC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(allC, freq.millis)
  }

  else if(!pids.isEmpty && apps.isEmpty) {
    val pidsC = fr.inria.powerapi.library.PIDS(pids: _*)
    allPIDs ++= (for(process <- pidsC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(pidsC, freq.millis)
  }

  else if(pids.isEmpty && !apps.isEmpty) {
    val appsC = fr.inria.powerapi.library.APPS(apps: _*)
    println(appsC)
    allPIDs ++= (for(process <- appsC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(appsC, freq.millis)
  }

  else {
    val pidsC = fr.inria.powerapi.library.PIDS(pids: _*)
    val appsC = fr.inria.powerapi.library.APPS(apps: _*)
    allPIDs ++= (for(process <- pidsC.monitoredProcesses.toArray) yield process.pid)
    allPIDs ++= (for(process <- appsC.monitoredProcesses.toArray) yield process.pid)
    powerapi.start(pidsC, appsC, freq.millis)
  }

  if(reporters.isEmpty) reporters = Array("chart")

  reporters.foreach(reporter => {
    monitoring = monitoring.attachReporter(getReporter(reporter))
  })

  monitoring.waitFor(time.minute)
  powerapi.stop

  val allDevs = Initializer.devs.distinct.sortWith(_.compareTo(_) < 0)
  
  // Create the gnuplot script to generate the graph
  if (reporters.contains("gnuplot")) {
    if (allPIDs.nonEmpty) GnuplotScript.create(allPIDs.map(_.toString).toList, filePath)
    else GnuplotScript.create(allDevs, filePath)
  }

  System.exit(0)
}
