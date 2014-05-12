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
package fr.inria.powerapi.formula.cpu.maxvm

import fr.inria.powerapi.core.{Energy, Formula}
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import fr.inria.powerapi.sensor.cpu.proc.virtio.CpuVirtioSensorMessage

import java.io.BufferedReader
import java.io.FileReader

/**
 * CpuFormula for virtual machines.
 * Implements a CpuFormula in making the ratio between maximum CPU power (obtained by getting values from the host
 * machine) and the process CPU usage obtained from the received CpuVirtioSensorMessage.
 */
class CpuFormula extends Formula {
  def messagesToListen = Array(classOf[CpuVirtioSensorMessage])

  def compute(now: CpuVirtioSensorMessage) = {
    if (log.isInfoEnabled) log.info("utilization: " + now.processPercent.percent)
    if (log.isInfoEnabled) log.info("activity percent: " + now.activityPercent.percent)

    // The division by 0 is not allowed and, the process and activity percents are lte to 100 percent.
    if (now.activityPercent.percent == 0) {
      Energy.fromPower(0)
    }

    else {
      var activityPercent = now.activityPercent.percent
      var processPercent = now.processPercent.percent

      if(activityPercent > 100.0) {
        activityPercent = 100.0
      }

      if(processPercent > 100) {
        processPercent = 100.0
      }

      Energy.fromPower((now.vmConsumption.power * processPercent) / activityPercent)
    }
  }

  def process(cpuVirtioSensorMessage: CpuVirtioSensorMessage) {
    publish(CpuFormulaMessage(compute(cpuVirtioSensorMessage), cpuVirtioSensorMessage.tick))
  }

  def acquire = {
    case cpuVirtioSensorMessage: CpuVirtioSensorMessage => process(cpuVirtioSensorMessage)
  }
}

/**
 * Companion object used to create this given component.
 */
object FormulaCpuMaxVM extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[CpuFormula]
}

/**
 * Use to cook the bake.
 */
trait FormulaCpuMaxVM {
  self: fr.inria.powerapi.core.API =>
  configure(FormulaCpuMaxVM)
}