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

import fr.inria.powerapi.core.{ Sensor, SensorMessage, Process, Tick, TickSubscription }
import fr.inria.powerapi.library.MonitoringMessages

import scala.collection
import scala.collection.JavaConversions

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }

import com.typesafe.config.Config

/**
 * Libpfm sensor messages.
 */
case class Counter(value: Long)
case class Event(name: String)

case class LibpfmSensorMessage(
  counter: Counter = Counter(0),
  event: Event = Event("none"),
  tick: Tick
) extends SensorMessage

/**
 * Libpfm sensor configuration.
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  /** Read all bit fields from the configuration. */
  lazy val bits = {
    collection.immutable.HashMap[Int, Int](
      0 -> load { _.getInt("powerapi.libpfm.configuration.disabled") }(0),
      1 -> load { _.getInt("powerapi.libpfm.configuration.inherit") }(0),
      2 -> load { _.getInt("powerapi.libpfm.configuration.pinned") }(0),
      3 -> load { _.getInt("powerapi.libpfm.configuration.exclusive") }(0),
      4 -> load { _.getInt("powerapi.libpfm.configuration.exclude_user") }(0),
      5 -> load { _.getInt("powerapi.libpfm.configuration.exclude_kernel") }(0),
      6 -> load { _.getInt("powerapi.libpfm.configuration.exclude_hv") }(0),
      7 -> load { _.getInt("powerapi.libpfm.configuration.exclude_idle") }(0),
      8 -> load { _.getInt("powerapi.libpfm.configuration.mmap") }(0),
      9 -> load { _.getInt("powerapi.libpfm.configuration.comm") }(0),
      10 -> load { _.getInt("powerapi.libpfm.configuration.freq") }(0),
      11 -> load { _.getInt("powerapi.libpfm.configuration.inherit_stat") }(0),
      12 -> load { _.getInt("powerapi.libpfm.configuration.enable_on_exec") }(0),
      13 -> load { _.getInt("powerapi.libpfm.configuration.task") }(0),
      14 -> load { _.getInt("powerapi.libpfm.configuration.watermark") }(0),
      15 -> load { _.getInt("powerapi.libpfm.configuration.precise_ip_1") }(0),
      16 -> load { _.getInt("powerapi.libpfm.configuration.precise_ip_2") }(0),
      17 -> load { _.getInt("powerapi.libpfm.configuration.mmap_data") }(0),
      18 -> load { _.getInt("powerapi.libpfm.configuration.sample_id_all") }(0),
      19 -> load { _.getInt("powerapi.libpfm.configuration.exclude_host") }(0),
      20 -> load { _.getInt("powerapi.libpfm.configuration.exclude_guest") }(1),
      21 -> load { _.getInt("powerapi.libpfm.configuration.exclude_callchain_kernel") }(0),
      22 -> load { _.getInt("powerapi.libpfm.configuration.exclude_callchain_user") }(0)
    )
  }

  /** Create the corresponding BitSet used to open the file descriptor. */
  lazy val bitset = {
    val tmp = new java.util.BitSet()
    bits.filter { 
      case (idx, bit) => bit == 1
    }.keys.foreach(idx => tmp.set(idx))
    
    tmp
  }

  /** Events to monitor. */
  lazy val events = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.events")))
        yield (item.asInstanceOf[Config].getString("event"))).toArray
  }(Array[String]())
}

/**
 * Sensor which opens one counter per event and pid (because of the implementation of perf_event_open method).
 */
class LibpfmSensor(event: String) extends Sensor with Configuration {
  import MonitoringMessages.CleanResources

  lazy val descriptors = new scala.collection.mutable.HashMap[Process, Int]
  // Used to close the file descriptors which are useless when a tick with a new timestamp is received.
  var tickProcesses = scala.collection.mutable.Set[Process]()
  lazy val cache = new scala.collection.mutable.HashMap[TickSubscription, Long]
  var timestamp = 0l

  override def messagesToListen = super.messagesToListen ++ Array(classOf[CleanResources])

  override def acquire: Receive = super.acquire orElse acquireStopMonitoring

  def acquireStopMonitoring: Receive = {
    case CleanResources => stopMonitoring()
  }

  override def postStop() = {
    stopMonitoring()
  }

  def stopMonitoring() = {
    descriptors.foreach {
      case (_, fd) => {
        LibpfmUtil.disableCounter(fd)
        LibpfmUtil.closeCounter(fd)
      }
    }

    descriptors.clear()
    cache.clear()
  }

  def refreshCache(subscription: TickSubscription, now: Long) = {
    cache += (subscription -> now)
  }

  def process(tick: Tick) = {
    // Piece of code used to refresh the file descriptors which are read by the sensors, the old ones are closed.
    if(tick.timestamp > timestamp) {
      val diff = descriptors -- tickProcesses
      diff.foreach {
        case (process, fd) => {
          LibpfmUtil.disableCounter(fd)
          LibpfmUtil.closeCounter(fd)
          descriptors -= process
        }
      }

      timestamp = tick.timestamp
      tickProcesses = scala.collection.mutable.Set[Process]()
    }

    // Add the process into the cache of tick processes, it's a set, so we can just add it (no doublon).
    tickProcesses += tick.subscription.process

    // Reset and enable the counter if the cache does not contain it.
    if(!descriptors.contains(tick.subscription.process)) {
      LibpfmUtil.configureCounter(tick.subscription.process, bitset, event) match {
        case Some(fd: Int) => {
          LibpfmUtil.resetCounter(fd)
          LibpfmUtil.enableCounter(fd)
          descriptors(tick.subscription.process) = fd
        }
        case None => if(log.isWarningEnabled) log.warning("Libpfm is not able to open the counter for the pid " + tick.subscription.process.pid + ".")
      }
    }

    // Check if the counter was correctly opened.
    if(descriptors.contains(tick.subscription.process)) {
      val now = LibpfmUtil.readCounter(descriptors(tick.subscription.process))
      val old = cache.getOrElse(tick.subscription, now)
      refreshCache(tick.subscription, now)

      val diff = now - old
      
      publish(LibpfmSensorMessage(
        counter = Counter(diff),
        event = Event(event),
        tick = tick
      ))
    }
  }
}

/**
 * Trait used to configure the default parameter for each sensor.
 */
trait LibpfmComponent {
  // Mandatory, it is a class, so it is possible to create it several times (true will have no effect).
  lazy val singleton = false
  lazy val underlyingClass = classOf[LibpfmSensor]
}

/**
 * Class used to create this given component.
 * Here, it is not a companion object because we have to configure multiple sensors.
 */
class SensorLibpfmConfigured(val event: String) extends fr.inria.powerapi.core.APIComponent with LibpfmComponent {
  override lazy val args = List(event)
}

/**
 * Use to cook the bake.
 */
trait SensorLibpfm extends Configuration {
  self: fr.inria.powerapi.core.API =>

  // One sensor per event.
  events.distinct.foreach(event => configure(new SensorLibpfmConfigured(event)))
}