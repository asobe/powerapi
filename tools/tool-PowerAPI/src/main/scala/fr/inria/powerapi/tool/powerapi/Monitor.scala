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

object Initializer {

  var devs = List[String]("cpu")

  def beforeStart(cpuSensor:String, cpuFormula:String,
                  memSensor:String, memFormula:String,
                  diskSensor:String, diskFormula:String) {
    Array(
      cpuSensor match {
	      case "cpu-proc"     => classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor]
	      case "cpu-proc-reg" => classOf[fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor]
        case "cpu-proc-virtio" => classOf[fr.inria.powerapi.sensor.cpu.proc.virtio.CpuSensor]  	
      },
      cpuFormula match {
	      case "cpu-max"    => classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula]
	      case "cpu-maxvm"  => classOf[fr.inria.powerapi.formula.cpu.maxvm.CpuFormula]
	      case "cpu-reg"    => classOf[fr.inria.powerapi.formula.cpu.reg.CpuFormula]
      }
    ).foreach(PowerAPI.startEnergyModule(_))
    
    if (memSensor != "" && memFormula != "") {
      Array(
        memSensor match {
	        case "mem-proc"  => classOf[fr.inria.powerapi.sensor.mem.proc.MemSensor]
	        case "mem-sigar" => classOf[fr.inria.powerapi.sensor.mem.sigar.MemSensor]
        },
        memFormula match {
	        case "mem-single" => classOf[fr.inria.powerapi.formula.mem.single.MemFormula]
        }
      ).foreach(PowerAPI.startEnergyModule(_))
      devs +:= "memory"
    }
    
    if (diskSensor != "" && diskFormula != "") {
      Array(
        diskSensor match {
	        case "disk-proc" => classOf[fr.inria.powerapi.sensor.disk.proc.DiskSensor]
	        case "disk-atop" => classOf[fr.inria.powerapi.sensor.disk.atop.DiskSensor] 	
        },
        diskFormula match {
	        case "disk-single" => classOf[fr.inria.powerapi.formula.disk.single.DiskFormula]
        }
      ).foreach(PowerAPI.startEnergyModule(_))
      devs +:= "disk"
    }
  }

  def beforeEnd(cpuSensor:String, cpuFormula:String,
                memSensor:String, memFormula:String,
                diskSensor:String, diskFormula:String) {
    Array(
      cpuSensor match {
	      case "cpu-proc"     => classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor]
	      case "cpu-proc-reg" => classOf[fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor]
        case "cpu-proc-virtio" => classOf[fr.inria.powerapi.sensor.cpu.proc.virtio.CpuSensor]	
      },
      cpuFormula match {
	      case "cpu-max"    => classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula]
	      case "cpu-maxvm"  => classOf[fr.inria.powerapi.formula.cpu.maxvm.CpuFormula]
	      case "cpu-reg"    => classOf[fr.inria.powerapi.formula.cpu.reg.CpuFormula]
      }
    ).foreach(PowerAPI.stopEnergyModule(_))
    
    if (memSensor != "" && memFormula != "")
      Array(
        memSensor match {
	        case "mem-proc"  => classOf[fr.inria.powerapi.sensor.mem.proc.MemSensor]
	        case "mem-sigar" => classOf[fr.inria.powerapi.sensor.mem.sigar.MemSensor]
        },
        memFormula match {
	        case "mem-single" => classOf[fr.inria.powerapi.formula.mem.single.MemFormula]
        }
      ).foreach(PowerAPI.stopEnergyModule(_))
    
    if (diskSensor != "" && diskFormula != "")
      Array(
        diskSensor match {
	        case "disk-proc" => classOf[fr.inria.powerapi.sensor.disk.proc.DiskSensor]
	        case "disk-atop" => classOf[fr.inria.powerapi.sensor.disk.atop.DiskSensor] 	
        },
        diskFormula match {
	        case "disk-single" => classOf[fr.inria.powerapi.formula.disk.single.DiskFormula]
        }
      ).foreach(PowerAPI.stopEnergyModule(_))
  }
}

object Monitor extends App {
  lazy val PidsFormat       = """-pid\s+(\d+[,\d]*)""".r
  lazy val VmsFormat        = """-vm\s+(\d+:\d+[,\d+:\d]*)""".r
  lazy val AppsFormat       = """-app\s+(.+[,.]*)""".r
  lazy val AppsContFormat   = """-appscont\s+(1|0)""".r
  lazy val AggregatorFormat = """-aggregator\s+(device|process)""".r
  lazy val OutputFormat     = """-output\s+(console|file|gnuplot|chart|virtio)""".r
  lazy val FileFormat       = """-filename\s+(\w+)""".r
  lazy val FreqFormat       = """-frequency\s+(\d+)""".r
  lazy val TimeFormat       = """-time\s+(\d+)""".r
  lazy val CpuSensorFormat   = """-cpusensor\s+(cpu-proc|cpu-proc-reg|cpu-proc-virtio)""".r
  lazy val CpuFormulaFormat  = """-cpuformula\s+(cpu-max|cpu-maxvm|cpu-reg)""".r
  lazy val MemSensorFormat   = """-memsensor\s+(mem-proc|mem-sigar)""".r
  lazy val MemFormulaFormat  = """-memformula\s+(mem-single)""".r
  lazy val DiskSensorFormat  = """-disksensor\s+(disk-proc|disk-atop)""".r
  lazy val DiskFormulaFormat = """-diskformula\s+(disk-single)""".r
  
  val params =
  (for (arg <- args) yield {
    arg match {
      case PidsFormat(pids)               => ("pids" -> pids)
      case VmsFormat(vm)                  => ("vm" -> vm)
      case AppsFormat(apps)               => ("apps" -> apps)
      case AppsContFormat(appscont)       => ("appscont" -> appscont)
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

  var pids = params.getOrElse("pids", "-1": String).split(',').map(_.toInt)
  if(params.isDefinedAt("vm")) {
    pids = createVMCOnfiguration(params("vm"))
  }
  
  Initializer.beforeStart(
    params.getOrElse("cpuSensor", "cpu-proc":String), params.getOrElse("cpuFormula", "cpu-max":String),
    params.getOrElse("memSensor", "":String), params.getOrElse("memFormula", "":String),
    params.getOrElse("diskSensor", "":String), params.getOrElse("diskFormula", "":String)
  )
  Processes.filePath = params.getOrElse("filePath", "powerapi-out": String)
  Processes.start(
    pids = pids.toList,
    apps = params.getOrElse("apps", "": String),
    agg  = params.getOrElse("agg", "timestamp": String),
    out  = params.getOrElse("out", "chart": String),
    freq = params.getOrElse("freq", "1000": String).toInt,
    time = params.getOrElse("time", "5": String).toInt,
    appscont = params.getOrElse("appscont","0": String).toInt,
    devs = Initializer.devs
  )
  Initializer.beforeEnd(
    params.getOrElse("cpuSensor", "cpu-proc":String), params.getOrElse("cpuFormula", "cpu-max":String),
    params.getOrElse("memSensor", "":String), params.getOrElse("memFormula", "":String),
    params.getOrElse("diskSensor", "":String), params.getOrElse("diskFormula", "":String)
  )
  System.exit(0)
}
