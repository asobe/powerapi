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
import org.apache.log4j.{ Level, Logger }

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
          if (logger.isEnabledFor(Level.WARN)) logger.warn("i/o exception: " + ioe.getMessage)
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
class CpuSensor extends fr.inria.powerapi.sensor.cpu.proc.reg.CpuSensor with Configuration {

  /**
   * Delegate class collecting time information contained into virtio file, providing process utilization and VM consumption from host
   */
  class VMConsumptionFromHost {
    lazy val cache = collection.mutable.Map[Long, Double]()
    
    def refreshCache(timestamp: Long, hostConsumption: Double) {
      cache += (timestamp -> hostConsumption)
    }
    
    def process(timestamp: Long) = {
      var power = cache.getOrElse(timestamp, 0.0)

      // Value not found in the cache, we have to read the consumption in the buffer
      if(power == 0.0) {
        if (br != null) {
          val readPower = br.readLine
          
          if (readPower == null) power = 0.0
          else {
            power = readPower.toDouble
            refreshCache(timestamp, power)
          }
        }
      }

      if (logger.isEnabledFor(Level.INFO)) logger.info("read power value from host: " + power)
      Energy.fromPower(power)
    }
  }

  lazy val vmConsumption = new VMConsumptionFromHost

  override def process(tick: Tick) {
    publish(
      CpuVirtioSensorMessage(
        vmConsumption = vmConsumption.process(tick.timestamp),
        processPercent = processPercent.process(tick.subscription),
        activityPercent = activityPercent.process(tick.subscription),
        tick = tick))
  }
}
