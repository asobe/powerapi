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
class LibpfmCoreSensor(event: String, bitset: java.util.BitSet, htIndexes: Array[Int]) extends Sensor {
  // [hyperthreadid -> fd]
  lazy val descriptors = {
    val buffer = scala.collection.mutable.HashMap[Int, Int]()
    htIndexes.foreach(htIndex => {
      val fd = LibpfmUtil.configureCounter(CID(htIndex), bitset, event) match {
        case Some(fd: Int) => {
          LibpfmUtil.resetCounter(fd)
          LibpfmUtil.enableCounter(fd)
          fd
        }
        case None => {
          if(log.isWarningEnabled) log.warning(s"Libpfm is not able to open the counter for the thread #$htIndex.")
          -1
        }
      }

      buffer += (htIndex -> fd)
    })

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

  def refreshCache(htIndex: Int, now: Array[Long]) = {
    cache += (htIndex -> now)
  }

  def refreshDeltaScaledCache(htIndex: Int, value: Long) = {
    deltaScaledCache += (htIndex -> value)
  }

  def process(tick: Tick) = {
    var deltaScaledVal = 0l

    htIndexes.foreach(htIndex => {
      if(descriptors.contains(htIndex)) {
        val fd = descriptors(htIndex)
      
        val now = LibpfmUtil.readCounter(descriptors(htIndex))
        val old = cache.getOrElse(htIndex, now)
        refreshCache(htIndex, now)
      
        deltaScaledVal = {
          // This may appear when the process exists but it does not execute any instructions, so we don't want to get the previous value.
          if(now(1) == old(1) && now(2) == old(2)) {
            // Put the ratio to one to get the non scaled value (see the scaling method).
            val fakeValues: Array[Long] = Array(old(0), (now(1) - 1), (now(2) - 1))
            LibpfmUtil.scale(now, fakeValues)
          }
          // This may appear if libpfm was not able to read the correct value (problem with the access to the counter).
          else if(now(2) == old(2)) {
            deltaScaledCache.getOrElse(fd, 0l)
          }
          else LibpfmUtil.scale(now, old)
        }
      
        refreshDeltaScaledCache(htIndex, deltaScaledVal)
      }

      publish(LibpfmSensorMessage(
        counter = Counter(deltaScaledVal),
        event = Event(event),
        tick = tick
      ))
    })
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
class SensorLibpfmCoreConfigured(val event: String, val bitset: java.util.BitSet, val threads: Array[Int]) extends fr.inria.powerapi.core.APIComponent with LibpfmCoreSensorComponent {
  override lazy val args = List(event, bitset, threads)
}

/**
* Use to cook the bake.
*/
trait SensorLibpfmCore extends LibpfmCoreConfiguration {
  self: fr.inria.powerapi.core.API =>

  // One sensor per event.
  events.distinct.foreach(event => configure(new SensorLibpfmCoreConfigured(event, bitset, htIndexes)))
}
