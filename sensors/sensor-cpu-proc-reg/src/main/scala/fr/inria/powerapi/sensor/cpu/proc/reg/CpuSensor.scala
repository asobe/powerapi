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
package fr.inria.powerapi.sensor.cpu.proc.reg

import java.io.FileInputStream
import java.io.IOException
import java.net.URL

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Sensor
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage
import fr.inria.powerapi.sensor.cpu.api.ActivityPercent
import scalax.io.Resource

/**
 * CPU sensor component that collects data from a /proc and /sys directories
 * which are typically presents under a Linux platform.
 *
 * @see http://www.kernel.org/doc/man-pages/online/pages/man5/proc.5.html
 *
 * @author lhuertas
 * @author mcolmant
 */
class CpuSensor extends fr.inria.powerapi.sensor.cpu.proc.CpuSensor {

  /**
   * Providing the CPU activity percent
   */
  class ActivityPercent() {

    def activityElapsedTime(times: Array[Long], globalElapsedTime: Long): Long = {
      // For the activity elapsed time, we take only the activity values from stat file
      times(0) + times(1) + times(2) + times(7)
    }

    // [TickSubscription, (globalElapsedTime, activityElapsedTime)]
    lazy val cache = collection.mutable.Map[TickSubscription, (Long, Long)]()
    def refrechCache(subscription: TickSubscription, now: (Long, Long)) {
      cache += (subscription -> now)
    }

    def process(subscription: TickSubscription) = {
      val times = processPercent.splittedTimes
      val globalElapsedTime = processPercent.globalElapsedTime(times)
      val now = (processPercent.globalElapsedTime(times), activityElapsedTime(times, globalElapsedTime))
      val old = cache.getOrElse(subscription, now)
      refrechCache(subscription, now)

      val globalDiff = now._1 - old._1
      val activityDiff = now._2 - old._2
      if (globalDiff == 0 || activityDiff == 0) {
        ActivityPercent(0)
      } else {
         ActivityPercent(activityDiff.doubleValue() / globalDiff)
      }

    }
  }

  lazy val activityPercent = new ActivityPercent

  override def process(tick: Tick) {
    publish(
      CpuSensorMessage(
        activityPercent = activityPercent.process(tick.subscription),
        processPercent = processPercent.process(tick.subscription),
        tick = tick
      )
    )
  }
}

/**
 * Companion object used to create this given component.
 */
object SensorCpuProcReg extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[CpuSensor]
}

/**
 * Use to cook the bake.
 */
trait SensorCpuProcReg {
  self: fr.inria.powerapi.core.API =>
  configure(SensorCpuProcReg)
}