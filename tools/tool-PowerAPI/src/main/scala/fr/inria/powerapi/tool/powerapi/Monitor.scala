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
import fr.inria.powerapi.reporter.console.ConsoleReporter
import fr.inria.powerapi.reporter.file.FileReporter
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter

object Initializer {
  def beforeStart(sensor:String,formula:String) {
    Array(
      sensor match {
	case "cpu-proc" => classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor]
	case "cpu-proc-reg" => classOf[fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor] 	
      }, 
      formula match {
	case "cpu-max" => classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula]
	case "cpu-maxvm" => classOf[fr.inria.powerapi.formula.cpu.maxvm.CpuFormula]
	case "cpu-reg" => classOf[fr.inria.powerapi.formula.cpu.reg.CpuFormula]
      }
    ).foreach(PowerAPI.startEnergyModule(_))
  }

  def beforeEnd(sensor:String,formula:String) {
    Array(
     sensor match {
        case "cpu-proc" => classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor]
        case "cpu-proc-reg" => classOf[fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor]
     },
     formula match {
        case "cpu-max" => classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula]
        case "cpu-maxvm" => classOf[fr.inria.powerapi.formula.cpu.maxvm.CpuFormula]
        case "cpu-reg" => classOf[fr.inria.powerapi.formula.cpu.reg.CpuFormula]
     }
    ).foreach(PowerAPI.stopEnergyModule(_))
  }
}

object Monitor extends App {
  lazy val PidsFormat   = """-pid\s+(\d+[,\d]*)""".r
  lazy val AppsFormat   = """-app\s+(\w+[,\w]*)""".r
  lazy val OutputFormat = """-output\s+(console|file|gnuplot|chart|virtio)""".r
  lazy val FileFormat   = """-filename\s+(\w+)""".r
  lazy val FreqFormat   = """-frequency\s+(\d+)""".r
  lazy val SensorFormat = """-sensor\s+(cpu-proc|cpu-proc-reg)""".r
  lazy val FormulaFormat = """-formula\s+(cpu-max|cpu-maxvm|cpu-reg)""".r
 
  val params =
  (for (arg <- args) yield {
    arg match {
      case PidsFormat(pids)     => ("pids" -> pids)
      case AppsFormat(apps)     => ("apps" -> apps)
      case OutputFormat(out)    => ("out" -> out)
      case FileFormat(filePath) => ("filePath" -> filePath)
      case FreqFormat(freq)     => ("freq" -> freq)
      case SensorFormat(sensor) => ("sensor" -> sensor)
      case FormulaFormat(formula) => ("formula" -> formula)
      case _ => ("none" -> "")
    }
  }).toMap
 
  Initializer.beforeStart(params.getOrElse("sensor","cpu-proc":String),params.getOrElse("formula","cpu-max":String))
  Processes.filePath = params.getOrElse("filePath", "powerapi-out": String)
  Processes.start(
    pids = params.getOrElse("pids", "-1": String).split(',').map(_.toInt),
    apps = params.getOrElse("apps", "": String),
    out = params.getOrElse("out", "chart": String),
    freq = params.getOrElse("freq", "1000": String).toInt
  )
  Initializer.beforeEnd(params.getOrElse("sensor","cpu-proc":String),params.getOrElse("formula","cpu-max":String))
  System.exit(0)
}
