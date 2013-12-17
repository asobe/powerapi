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
package fr.inria.powerapi.sensor.cpu.proc.virtio

import java.io.FileInputStream
import java.io.IOException
import java.net.URL
import java.io.BufferedReader
import java.io.FileReader

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Sensor
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.core.SensorMessage

import fr.inria.powerapi.sensor.cpu.api.TimeInStates
import fr.inria.powerapi.sensor.cpu.api.ActivityPercent
import fr.inria.powerapi.sensor.cpu.api.GlobalElapsedTime
import fr.inria.powerapi.sensor.cpu.api.ProcessElapsedTime
import fr.inria.powerapi.sensor.cpu.api.ProcessPercent

import scalax.io.Resource

/**
 * CPU sensor configuration.
 *
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  lazy val filepath = load { _.getString("powerapi.cpu.virtio.vm") }("/dev/virtio-ports/no")
  lazy val br = try {
    new BufferedReader(new FileReader(filepath))
  } catch {
        case ioe: IOException =>
          if (log.isWarningEnabled) log.warning("i/o exception: " + ioe.getMessage)
          null
    }
}

case class CpuVirtioSensorMessage(
  timeInStates: TimeInStates = TimeInStates(Map[Int, Long]()),
  vmConsumption: Energy = Energy.fromPower(-1),
  activityPercent: ActivityPercent = ActivityPercent(-1),
  globalElapsedTime: GlobalElapsedTime = GlobalElapsedTime(-1),
  processElapsedTime: ProcessElapsedTime = ProcessElapsedTime(-1),
  processPercent: ProcessPercent = ProcessPercent(-1),
  tick: Tick) extends SensorMessage

/**
 * CPU sensor component that collects data from virtio serial port
 */
class CpuSensor extends fr.inria.powerapi.sensor.cpu.proc.CpuSensor with Configuration {

  /**
   * Delegate class collecting time information contained into virtio file, providing process utilization and VM consumption from host
   */
  class VMConsumptionFromHost {
    
    def process = {
      var power = "0.0"

      if (br != null) {
        power = br.readLine()
        if (power == null) power = "0.0"

        println("read power value from host: ", power)
      }

      Energy.fromPower(power.toDouble)
    }
  }

  lazy val vmConsumption = new VMConsumptionFromHost

  override def process(tick: Tick) {
    publish(
      CpuVirtioSensorMessage(
        vmConsumption = vmConsumption.process,
        processPercent = processPercent.process(tick.subscription),
        tick = tick))
  }
}