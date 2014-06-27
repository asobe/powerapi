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
package fr.inria.powerapi.sensor.libpfm

import fr.inria.powerapi.core.{ CID, Sensor, Tick }

/**
* Sensor which opens one counter per event and core/thread (because of the implementation of perf_event_open method).
*/
class LibpfmCoreSensor(event: String) extends Sensor with LibpfmCoreConfiguration {
  lazy val descriptors = {
    val buffer = scala.collection.mutable.HashMap[Int, Int]()
    for(thread <- 0 until threads) {
      val fd = LibpfmUtil.configureCounter(CID(thread), bitset, event) match {
        case Some(fd: Int) => {
          LibpfmUtil.resetCounter(fd)
          LibpfmUtil.enableCounter(fd)
          fd
        }
        case None => {
          if(log.isWarningEnabled) log.warning(s"Libpfm is not able to open the counter for the thread #$thread.")
          -1
        }
      }

      buffer += (thread -> fd)
    }

    buffer
  }

  lazy val cache = new scala.collection.mutable.HashMap[Int, Array[Long]]
  lazy val deltaScaledCache = new scala.collection.mutable.HashMap[Int, Long]

  override def postStop() = {
    descriptors.foreach {
      case (_, fd) => {
        LibpfmUtil.disableCounter(fd)
        LibpfmUtil.closeCounter(fd)
      }
    }

    descriptors.clear()
    cache.clear()
    deltaScaledCache.clear()
  }

  def refreshCache(thread: Int, now: Array[Long]) = {
    cache += (thread -> now)
  }

  def refreshDeltaScaledCache(thread: Int, value: Long) = {
    deltaScaledCache += (thread -> value)
  }

  def process(tick: Tick) = {
    var deltaScaledVal = 0l

    for(thread <- 0 until threads) {
      val now = LibpfmUtil.readCounter(descriptors(thread))
      val old = cache.getOrElse(thread, now)

      refreshCache(thread, now)

      var threadDeltaScaledVal = LibpfmUtil.scale(now, old)

      // The diff can be set to 0 because of the enabled times read from the counters (same for the current reading and the old one).
      if(threadDeltaScaledVal == 0) {
        val oldThreadDeltaScaledVal = deltaScaledCache.getOrElse(thread, 0l)
        threadDeltaScaledVal = oldThreadDeltaScaledVal
      }
      
      else refreshDeltaScaledCache(thread, threadDeltaScaledVal)

      deltaScaledVal += threadDeltaScaledVal
    }

    publish(LibpfmSensorMessage(
      counter = Counter(deltaScaledVal),
      event = Event(event),
      tick = tick
    ))
  }
}

/**
* Trait used to configure the default parameter for each sensor.
*/
trait LibpfmCoreSensorComponent {
  // Mandatory, it is a class, so it is possible to create it several times (true will have no effect).
  lazy val singleton = false
  lazy val underlyingClass = classOf[LibpfmCoreSensor]
}

/**
* Class used to create this given component.
* Here, it is not a companion object because we have to configure multiple sensors.
*/
class SensorLibpfmCoreConfigured(val event: String) extends fr.inria.powerapi.core.APIComponent with LibpfmCoreSensorComponent {
  override lazy val args = List(event)
}

/**
* Use to cook the bake.
*/
trait SensorLibpfmCore extends LibpfmCoreConfiguration {
  self: fr.inria.powerapi.core.API =>

  // One sensor per event.
  events.distinct.foreach(event => configure(new SensorLibpfmCoreConfigured(event)))
}