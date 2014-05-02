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
package fr.inria.powerapi.formula.libpfm

import fr.inria.powerapi.core.{ Component, Energy, Formula, FormulaMessage, Message, Tick, TickSubscription }
import fr.inria.powerapi.sensor.libpfm.{ Counter, Event, LibpfmSensorMessage }
import fr.inria.powerapi.sensor.cpu.api.TimeInStates

import scala.collection
import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import scalax.file.Path
import com.typesafe.config.Config

/**
 * Configuration part.
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  lazy val formulae = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.formulae")))
        yield (item.asInstanceOf[Config].getLong("freq"), JavaConversions.asScalaBuffer(item.asInstanceOf[Config].getDoubleList("formula").map(_.toDouble)).toArray)).toMap
  } (Map[Long, Array[Double]]())

  lazy val events = (load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.events")))
        yield (item.asInstanceOf[Config].getString("event"))).toArray
  } (Array[String]())).sorted

  /** Thread numbers. */
  lazy val threads = load { _.getInt("powerapi.cpu.threads") }(0)
  /** Option used to know if cpufreq is enable or not. */
  lazy val cpuFreq = load { _.getBoolean("powerapi.cpu.cpufreq-utils") }(false)
  /** Path to time_in_state file. */
  lazy val timeInStatePath = load { _.getString("powerapi.cpu.time-in-state") }("/sys/devices/system/cpu/cpu%?/cpufreq/stats/time_in_state")
}

/**
 * Messages.
 */
case class LibpfmListenerMessage(tick: Tick, timeInStates: TimeInStates = TimeInStates(Map[Int, Long]()), messages: List[LibpfmSensorMessage]) extends Message
case class LibpfmFormulaMessage(tick: Tick, energy: Energy, device: String = "cpu") extends FormulaMessage

/**
 * This actor is used to delegate the processing of LibpfmSensorMessage. Indeed, one sensor message is published by event/process,
 * so we have to aggregate them before to publish formula message into the event bus.
 * A cache is used to retrieve the sensor messages and store them for a given tick. When a new timestamp is detected, the messages for
 * the process are published.
 */
class LibpfmListener extends Component with Configuration {
  /**
   * Delegate class to deal with time spent within each CPU frequencies.
   */
  class Frequencies {
    // time_in_state line format: frequency time
    lazy val TimeInStateFormat = """(\d+)\s+(\d+)""".r
    def timeInStates = {
      val result = scala.collection.mutable.HashMap[Int, Long]()

      (for (thread <- 0 until threads) yield (timeInStatePath replace ("%?", thread.toString))).foreach(timeInStateFile => {
        val lines = Path.fromString(timeInStateFile).lines()
        lines.foreach(line => {
          line match {
            case TimeInStateFormat(freq, t) => result += (freq.toInt -> (t.toLong + (result getOrElse (freq.toInt, 0: Long))))
            case _ => if (log.isWarningEnabled) log.warning("unable to parse line \"" + line + "\" from file \"" + timeInStateFile)
          }
        })
      })

      result.toMap[Int, Long]
    }

    lazy val cache = scala.collection.mutable.HashMap[TickSubscription, TimeInStates]()
    def refreshCache(subscription: TickSubscription, now: TimeInStates) {
      cache += (subscription -> now)
    }

    def process(subscription: TickSubscription) = {
      val now = TimeInStates(timeInStates)
      val old = cache getOrElse (subscription, now)
      refreshCache(subscription, now)
      now - old
    }
  }

  lazy val frequencies = new Frequencies
  def messagesToListen = Array(classOf[LibpfmSensorMessage])

  lazy val cache = scala.collection.mutable.HashMap[Tick, scala.collection.mutable.ListBuffer[LibpfmSensorMessage]]()

  def acquire = {
    case libpfmSensorMessage: LibpfmSensorMessage => process(libpfmSensorMessage)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def addToCache(libpfmSensorMessage: LibpfmSensorMessage) = {
    cache.get(libpfmSensorMessage.tick) match {
      case Some(buffer) => buffer += libpfmSensorMessage
      case None => {
        val buffer = scala.collection.mutable.ListBuffer[LibpfmSensorMessage]()
        buffer += libpfmSensorMessage
        cache += (libpfmSensorMessage.tick -> buffer)
      }
    }
  }

  def process(libpfmSensorMessage: LibpfmSensorMessage) = {
    // Cache is filtered, we send the messages only if we received a new tick for a process in a given monitoring.
    val filteredEntry = cache.filter(entry => {
      entry._1.clockid == libpfmSensorMessage.tick.clockid &&
      entry._1.subscription.process == libpfmSensorMessage.tick.subscription.process
    })
    
    // Different timestamp, we send a Formula message whicn contains all sensor messages for the process.
    if(!cache.isEmpty && !filteredEntry.isEmpty && !filteredEntry.contains(libpfmSensorMessage.tick)) {
      // If the size is greater than 1, the process is corrupt.
      if(filteredEntry.size == 1) {
        val entry = filteredEntry.head

        if(cpuFreq) {
          publish(LibpfmListenerMessage(tick = entry._1, timeInStates = frequencies.process(entry._1.subscription), messages = entry._2.toList))
        }

        else publish(LibpfmListenerMessage(tick = entry._1, messages = entry._2.toList))
        
        cache -= entry._1
      }
      else throw new Exception("There is a problem with the messages processing ...")
    }

    addToCache(libpfmSensorMessage)
  }
}

/**
 * This actor is responsible to compute the energy consumption with LibpfmListenerMessage which are pusblished on the bus.
 * One LibpfmListenerMessage contains all the necessary informations to inject the parameter inside the formulae.
 * Also, it takes into account DVFS with the time_in_state file which is provided by the cpufrequtils tool.
 * Be careful, some kernel versions have bugs with it (it works fine with a kernel 3.8).
 */
class LibpfmFormula extends Formula with Configuration {
  def messagesToListen = Array(classOf[LibpfmListenerMessage])

  def acquire = {
    case libpfmListenerMessage: LibpfmListenerMessage => process(libpfmListenerMessage)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def process(libpfmListenerMessage: LibpfmListenerMessage) = {
    var acc = 0.0

    def compute(formula: Array[Double], libpfmListenerMessage: LibpfmListenerMessage) = {
      var power = 0.0

      for(i <- 0 until (formula.size - 1)) {
        val eventMsg = libpfmListenerMessage.messages.filter(message => message.event.name == events(i))
        
        if(eventMsg.size != 1) throw new Exception("The processing is incorrect ...")
        
        if(libpfmListenerMessage.tick.subscription.duration != Duration.Zero) {
          power += (formula(i) * (eventMsg(0).counter.value / eventMsg(0).tick.subscription.duration.toSeconds))
        }
      }

      power
    }

    // We assume the order is the same (sorted).
    // Variables injection into the formula.
    if(cpuFreq) {
      for((freq, formula) <- formulae) {
        val power = compute(formula, libpfmListenerMessage)
        val globalTime = libpfmListenerMessage.timeInStates.times.values.sum
        var ratio = 0.0
        if(globalTime > 0) {
          ratio = libpfmListenerMessage.timeInStates.times(freq.toInt).toDouble / globalTime
        }
        acc += power * ratio
      }
    }

    else {
      val formula = formulae.maxBy(_._1)._2
      val power = compute(formula, libpfmListenerMessage)
      acc = power
    }

    publish(LibpfmFormulaMessage(energy = Energy.fromPower(acc), tick = libpfmListenerMessage.tick))
  }
}

/**
 * Companion object used to create this given component.
 */
object FormulaLibpfm extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[LibpfmFormula]
}

object FormulaListener extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[LibpfmListener]
}

/**
 * Use to cook the bake.
 */
trait FormulaLibpfm {
  self: fr.inria.powerapi.core.API =>
  configure(FormulaListener)
  configure(FormulaLibpfm)
}