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

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage

import java.io.BufferedReader
import java.io.FileReader

/**
 * CpuFormula for virtual machines.
 * Implements a CpuFormula in making the ratio between maximum CPU power (obtained by getting values from the host
 * machine) and the process CPU usage obtained from the received CpuSensorMessage.
 */

trait Configuration extends fr.inria.powerapi.core.Configuration {
  /**
   * Get the configuration for VirtioSerial.
   */
  lazy val filepath = load { _.getString("powerapi.cpu.virtio.vm") }("")
}

class CpuFormula extends fr.inria.powerapi.formula.cpu.api.CpuFormula with Configuration {
  //val filepath = "/dev/virtio-ports/port.2" // TODO: add this to config file
  var br = new BufferedReader(new FileReader(filepath))

  def compute(now: CpuSensorMessage) = {
    val power = br.readLine()
    println("read power value from host: ", power)
    Energy.fromPower(power.toDouble * now.processPercent.percent)
  }

  def process(cpuSensorMessage: CpuSensorMessage) {
    publish(CpuFormulaMessage(compute(cpuSensorMessage), cpuSensorMessage.tick))
  }

}
