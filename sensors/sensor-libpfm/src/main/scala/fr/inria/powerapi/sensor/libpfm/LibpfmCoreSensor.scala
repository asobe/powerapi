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
* Sensor which aggegrates the values per core for one event.
*/
class LibpfmCoreSensor(val event: String, val bitset: java.util.BitSet, val coreId: Int, val osIndexes: Array[Int]) extends Sensor {
  // [osIndexes -> fd]
  val descriptors = {
    val buffer = new scala.collection.mutable.HashMap[Int, Int]
    osIndexes.par.foreach(osIndex => {
      val fd = LibpfmUtil.configureCounter(CID(osIndex), bitset, event) match {
        case Some(fd: Int) => {
          LibpfmUtil.resetCounter(fd)
          LibpfmUtil.enableCounter(fd)
          fd
        }
        case None => {
          if(log.isWarningEnabled) log.warning(s"Libpfm is not able to open the counter for the core os#$osIndex.")
          -1
        }
      }

      buffer += (osIndex -> fd)
    })

    buffer
  }

  val cache = new scala.collection.mutable.HashMap[Int, Array[Long]]
  val deltaScaledCache = new scala.collection.mutable.HashMap[Int, Long]

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

  def refreshCache(osIndex: Int, now: Array[Long]) = {
    cache += (osIndex -> now)
  }

  def refreshDeltaScaledCache(osIndex: Int, value: Long) = {
    deltaScaledCache += (osIndex -> value)
  }

  def process(tick: Tick) = {
    var coreValue = 0l

    osIndexes.foreach(osIndex => {
      if(descriptors.contains(osIndex)) {
        val fd = descriptors(osIndex)
      
        val now = LibpfmUtil.readCounter(descriptors(osIndex))
        val old = cache.getOrElse(osIndex, now)
        refreshCache(osIndex, now)
      
        val deltaScaledVal = {
          // This may appear when the counter exists but it was not stressed, thus the previous value is useless.
          if(now(1) == old(1) && now(2) == old(2)) {
            // Put the ratio to one to get the non scaled value (see the scaling method).
            val fakeValues: Array[Long] = Array(old(0), (now(1) - 1), (now(2) - 1))
            LibpfmUtil.scale(now, fakeValues)
          }
          // This may appear if libpfm was not able to read the correct value (problem for accessing the counter).
          else if(now(2) == old(2)) {
            deltaScaledCache.getOrElse(fd, 0l)
          }
          else LibpfmUtil.scale(now, old)
        }
      
        refreshDeltaScaledCache(osIndex, deltaScaledVal)
        coreValue += deltaScaledVal
      }
    })

    publish(LibpfmCoreSensorMessage(
      core = Core(coreId),
      counter = Counter(coreValue),
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
class SensorLibpfmCoreConfigured(val event: String, val bitset: java.util.BitSet, val coreId: Int, val osIndexes: Array[Int]) extends fr.inria.powerapi.core.APIComponent with LibpfmCoreSensorComponent {
  override lazy val args = List(event, bitset, coreId, osIndexes)
}

/**
* Used to cook the bake.
* TODO: use hwloc to get the processor topology?
*/
trait SensorLibpfmCore extends LibpfmCoreConfiguration {
  self: fr.inria.powerapi.core.API =>
  
  for((core, osIndexes) <- topology) {
    for(event <- events.distinct) {
      // One sensor per core, per event.
      configure(new SensorLibpfmCoreConfigured(event, bitset, core, osIndexes))
    }
  }
}
