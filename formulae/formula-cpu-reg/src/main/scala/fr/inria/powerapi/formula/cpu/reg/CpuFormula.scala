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
package fr.inria.powerapi.formula.cpu.reg

import scala.collection.JavaConversions
import scala.collection.mutable

import com.typesafe.config.Config

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage

/**
 * CpuFormula configuration part.
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {

  /**
   * Polynomial coefficients.
   */
    lazy val coeffs = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.formula.coeffs")))
        yield (item.asInstanceOf[Config].getDouble("value"))).toArray
    }(Array[Double]())
}

/**
 * Implements a CpuFormula in making the ratio between CPU power (obtained by
 * the polynomial curve fitting) and the process CPU usage obtained from
 * the received CpuSensorMessage.
 */
class CpuFormula extends fr.inria.powerapi.formula.cpu.api.CpuFormula with Configuration {

  def compute(now: CpuSensorMessage) = {
    val CPUpower = breeze.numerics.polyval(coeffs, now.activityPercent.percent)

    if (now.activityPercent.percent == 0)
      Energy.fromPower(0)
    else
      Energy.fromPower(((now.processPercent.percent * CPUpower).doubleValue()) / now.activityPercent.percent)
  }

  def process(cpuSensorMessage: CpuSensorMessage) {
    publish(CpuFormulaMessage(compute(cpuSensorMessage), cpuSensorMessage.tick))
  }

}
