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
package fr.inria.powerapi.example.regression

import fr.inria.powerapi.library.PowerAPI

object Initializer {
  def beforeStart() {
    Array(
      classOf[fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor],
      classOf[fr.inria.powerapi.formula.cpu.reg.CpuFormula]
    ).foreach(PowerAPI.startEnergyModule(_))
  }

  def beforeEnd() {
    Array(
      classOf[fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor],
      classOf[fr.inria.powerapi.formula.cpu.reg.CpuFormula]
    ).foreach(PowerAPI.stopEnergyModule(_))
  }
}

object Monitor extends App {
  Initializer.beforeStart
  Processes.allToChart
  Initializer.beforeEnd
  System.exit(0)
}
