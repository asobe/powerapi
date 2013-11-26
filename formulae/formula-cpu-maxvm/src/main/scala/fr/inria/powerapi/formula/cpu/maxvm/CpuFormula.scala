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

import scala.collection.mutable

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage

import java.io.BufferedReader
import java.io.FileReader

/**
 * CpuFormula configuration part.
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {

  /**
   * CPU Thermal Design Power (TDP) value.
   *
   * @see http://en.wikipedia.org/wiki/Thermal_design_power
   */
  lazy val tdp = load { _.getInt("powerapi.cpu.tdp") }(0)

  /**
   * CPU Thermal Design Power (TDP) factor.
   * Not required but 0.7 as default [1].
   *
   * @see [1], JouleSort: A Balanced Energy-Efﬁciency Benchmark, by Rivoire et al.
   */
  lazy val tdpFactor = load (_.getDouble("powerapi.cpu.tdp-factor"), false) (0.7)
}

/**
 * Implements a CpuFormula in making the ratio between maximum CPU power (obtained by multiplying
 * its Thermal Design Power (TDP) value by a specific factor) and the process CPU usage obtained from
 * the received CpuSensorMessage.
 *
 * @see http://en.wikipedia.org/wiki/Thermal_design_power
 */
class CpuFormula extends fr.inria.powerapi.formula.cpu.api.CpuFormula with Configuration {
  //lazy val power = tdp * tdpFactor
  //lazy val power = 7.0 // just for testing, assuming the vm process in the host consumes 17Watt, should be replaced by dynamic value received from host
  val filepath = "/dev/virtio-ports/port.2"
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
