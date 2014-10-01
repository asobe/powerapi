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

import fr.inria.powerapi.core.{ Component, Energy, Formula, Message, Process, Tick, TickSubscription }
import fr.inria.powerapi.sensor.libpfm.{ Core, Counter, Event, LibpfmCoreSensorMessage }

class LibpfmCoreCyclesListener extends Component with LibpfmCoreCyclesFormulaConfiguration {
  def messagesToListen = Array(classOf[LibpfmCoreSensorMessage])
  // (clockid, timestamp)
  val cache = scala.collection.mutable.HashMap[(Long, Long), LibpfmAggregatedMessage]()

  def acquire = {
    case sensorMessage: LibpfmCoreSensorMessage => process(sensorMessage)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def addToCache(sensorMessage: LibpfmCoreSensorMessage) = {
    cache.get((sensorMessage.tick.subscription.clockid, sensorMessage.tick.timestamp)) match {
      case Some(agg) => agg += LibpfmRowMessage(sensorMessage.core, sensorMessage.counter, sensorMessage.event, sensorMessage.tick)
      case None => {
        val agg = LibpfmAggregatedMessage(tick = Tick(subscription = TickSubscription(sensorMessage.tick.subscription.clockid, Process(-1), sensorMessage.tick.subscription.duration), timestamp = sensorMessage.tick.timestamp))
        agg += LibpfmRowMessage(sensorMessage.core, sensorMessage.counter, sensorMessage.event, sensorMessage.tick)
        cache += ((sensorMessage.tick.subscription.clockid, sensorMessage.tick.timestamp) -> agg)
      }
    }
  }

  def dropFromCache(key: (Long, Long)) = {
    cache -= key
  }

  def process(sensorMessage: LibpfmCoreSensorMessage) = {
    // Cache is filtered, we send the messages only if we received a new tick for a given monitoring.
    val filteredEntry = cache.filter(entry => {
      entry._1._1 == sensorMessage.tick.subscription.clockid
    })
    
    // Different timestamp, we send a message which contains all sensor messages for the process.
    if(!filteredEntry.isEmpty && !filteredEntry.contains((sensorMessage.tick.subscription.clockid, sensorMessage.tick.timestamp))) {
      val key = filteredEntry.minBy(_._1._2)
      val clockid = key._1._1
      val timestamp = key._1._2
      val base = filteredEntry((clockid, timestamp))
      
      for(byProcess <- base.messages.groupBy(_.tick.subscription.process)) {
        publish(LibpfmAggregatedMessage(
          tick = Tick(TickSubscription(clockid, byProcess._1, base.tick.subscription.duration), timestamp),
          messages = byProcess._2
        ))
      }

      dropFromCache((clockid, timestamp))
    }
   
    addToCache(sensorMessage)
  }
}

class LibpfmCoreCyclesFormula extends Formula {
  def messagesToListen = Array(classOf[LibpfmAggregatedMessage])

  def acquire = {
    case libpfmAggregatedMessage: LibpfmAggregatedMessage => process(libpfmAggregatedMessage)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def process(libpfmAggregatedMessage: LibpfmAggregatedMessage) = {
    val (maxCoefficient, energy) = libpfmAggregatedMessage.energy
    publish(LibpfmCoreCyclesFormulaMessage(energy = energy, maxCoefficient = maxCoefficient, tick = libpfmAggregatedMessage.tick))
  }
}

/**
 * Companion object used to create this given component.
 */
object FormulaLibpfmCoreCyclesComponent extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[LibpfmCoreCyclesFormula]
}

object ListenerLibpfmCoreCyclesComponent extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[LibpfmCoreCyclesListener]
}

/**
 * Use to cook the bake.
 */
trait FormulaLibpfmCoreCycles {
  self: fr.inria.powerapi.core.API =>
  configure(FormulaLibpfmCoreCyclesComponent)
  configure(ListenerLibpfmCoreCyclesComponent)
}
