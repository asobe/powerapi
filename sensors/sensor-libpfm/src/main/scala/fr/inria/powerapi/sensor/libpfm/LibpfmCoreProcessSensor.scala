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

import fr.inria.powerapi.core.{ Sensor, Process, Tick, TCID }

/**
 * Sensor which opens a counter per thread/event/os core.
 */
class LibpfmCoreProcessSensor(val event: String, val bitset: java.util.BitSet, val coreId: Int, val osIndexes: Array[Int]) extends Sensor {
  // pid -> threads
  lazy val processes = scala.collection.mutable.HashMap[Process, Set[Int]]()
  lazy val tickProcesses = scala.collection.mutable.Set[Process]()
  // tid -> [fdOsIndex0, fdOsIndex1 ...]
  lazy val descriptors = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Int]]()
  var timestamp = 0l
  // fd -> values
  /* values[0] = raw count
   * values[1] = TIME_ENABLED
   * values[2] = TIME_RUNNING
   */
  lazy val cache = scala.collection.mutable.HashMap[Int, Array[Long]]()
  lazy val deltaScaledCache = scala.collection.mutable.HashMap[Int, Long]()

  override def postStop() = {
    descriptors.values.flatten.foreach(fd => {
      LibpfmUtil.disableCounter(fd)
      LibpfmUtil.closeCounter(fd)
    })

    processes.clear()
    tickProcesses.clear()
    descriptors.clear()
    timestamp = 0l
    cache.clear()
    deltaScaledCache.clear()
  }

  def refreshCache(fd: Int, now: Array[Long]) = {
    cache += (fd -> now)
  }

  def refreshDeltaScaledCache(fd: Int, value: Long) = {
    deltaScaledCache += (fd -> value)
  }

  def addDescriptors(tid: Int) = {
    if(!descriptors.contains(tid)) {
      descriptors(tid) = scala.collection.mutable.ArrayBuffer[Int]()
    }
    
    osIndexes.foreach(cid => {
      LibpfmUtil.configureCounter(TCID(tid, cid), bitset, event) match {
        case Some(fd: Int) => {  
          LibpfmUtil.resetCounter(fd)
          LibpfmUtil.enableCounter(fd)
          descriptors(tid) += fd  
        } 
        case _ => None
      } 
    })
  }

  def deleteDescriptors(tid: Int) = {
    if(descriptors.contains(tid)) {
      val fds = descriptors(tid)
      fds.foreach(fd => {
        LibpfmUtil.disableCounter(fd)
        LibpfmUtil.closeCounter(fd)
        cache -= fd
        deltaScaledCache -= fd
      })
      descriptors -= tid
    }
  }

  def process(tick: Tick) = {
    // Piece of code used to refresh the file descriptors which are read by the sensors, the old ones are closed.
    if(tick.timestamp > timestamp) {
      val diff = processes -- tickProcesses
      
      diff.foreach {
        case (process, tids) => {
          tids.foreach(tid => deleteDescriptors(tid))        
          processes -= process
        }
      }

      timestamp = tick.timestamp
      tickProcesses.clear()
    }

    tickProcesses += tick.subscription.process

    // Get the associated threads for a given process.
    val threads = {
      if(bitset.get(1)) {
        tick.subscription.process.threads + tick.subscription.process.pid 
      }
      else Set[Int](tick.subscription.process.pid)
    }

    // Reset and enable the counters + update caches if it's a new process.
    if(!processes.contains(tick.subscription.process)) {
      threads.foreach(tid => addDescriptors(tid))
      processes(tick.subscription.process) = threads
    }

    // Update the underlying threads for the given process.
    val oldTids = processes(tick.subscription.process) -- threads
    val newTids = threads -- processes(tick.subscription.process)

    oldTids.foreach(tid => deleteDescriptors(tid))
    newTids.foreach(tid => addDescriptors(tid))

    processes(tick.subscription.process) --= oldTids
    processes(tick.subscription.process) ++= newTids

    var deltaScaledVal = 0l

    processes(tick.subscription.process).foreach(tid => {
      if(descriptors.contains(tid)) {
        val fds = descriptors(tid)

        fds.foreach(fd => {
          val now = LibpfmUtil.readCounter(fd)
          val old = cache.getOrElse(fd, now)
          refreshCache(fd, now)

          val deltaScaledValTid = {
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

          refreshDeltaScaledCache(fd, deltaScaledValTid)
          deltaScaledVal += deltaScaledValTid
        })
      }
    })

    publish(LibpfmCoreSensorMessage(
      core = Core(coreId),
      counter = Counter(deltaScaledVal),
      event = Event(event),
      tick = tick
    ))
  }
}

/**
 * Trait used to configure the default parameter for each sensor.
 */
trait LibpfmCoreProcessSensorComponent {
  // Mandatory, it is a class, so it is possible to create it several times (true will have no effect).
  lazy val singleton = false
  lazy val underlyingClass = classOf[LibpfmCoreProcessSensor]
}

/**
 * Class used to create this given component.
 * Here, it is not a companion object because we have to configure multiple sensors.
 */
class SensorLibpfmCoreProcessConfigured(val event: String, val bitset: java.util.BitSet, val coreId: Int, val osIndexes: Array[Int]) extends fr.inria.powerapi.core.APIComponent with LibpfmCoreProcessSensorComponent {
  override lazy val args = List(event, bitset, coreId, osIndexes)
}

/**
 * Use to cook the bake.
 */
trait SensorLibpfmCoreProcess extends LibpfmCoreConfiguration {
  self: fr.inria.powerapi.core.API =>

  for((core, osIndexes) <- topology) {
    for(event <- events.distinct) {
      // One sensor per core, per event.
      configure(new SensorLibpfmCoreProcessConfigured(event, bitset, core, osIndexes))
    }
  }
}
