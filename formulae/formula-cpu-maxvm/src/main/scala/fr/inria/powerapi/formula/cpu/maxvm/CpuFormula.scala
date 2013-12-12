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
  lazy val filepath = load { _.getString("powerapi.cpu.virtio.vm") }("/dev/virtio-ports/")
  lazy val vmsConfiguration = Map[Int,Int]()
}

class CpuFormula extends fr.inria.powerapi.formula.cpu.api.CpuFormula with Configuration {
  lazy val files = scala.collection.mutable.Map.empty[Int, BufferedReader] 
  println("vmsconf: "+vmsConfiguration)
  for((vmPid, port) <- vmsConfiguration) {
	println("process ",vmPid," and will open port number ",port)
	var br = new BufferedReader(new FileReader(filepath+"port."+port))	
	files(vmPid) = br	
  }

  def compute(now: CpuSensorMessage) = {
    var power = files(now.tick.subscription.process.pid).readLine()
    if (power == null) { power = "0.0" }
    println("read power value from host: ", power)
    println("utilization: ",now.processPercent.percent)
    Energy.fromPower(power.toDouble * now.processPercent.percent)
  }

  def process(cpuSensorMessage: CpuSensorMessage) {
    publish(CpuFormulaMessage(compute(cpuSensorMessage), cpuSensorMessage.tick))
  }

}