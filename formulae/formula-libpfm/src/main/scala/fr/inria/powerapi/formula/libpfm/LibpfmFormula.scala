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

import fr.inria.powerapi.core.{ Energy, Formula, FormulaMessage, Tick, TickSubscription }
import fr.inria.powerapi.sensor.libpfm.{ Counter, Event, LibpfmSensorMessage }

import scala.collection
import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

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

  lazy val idlePower = load { _.getDouble("powerapi.libpfm.idle-power") }(0)

  lazy val events = (load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.events")))
        yield (item.asInstanceOf[Config].getString("event"))).toArray
  } (Array[String]())).sorted
}

/**
 * Represent the formula which is published on the event bus. Here, we store all the LibpfmSensorMessage on a cache system until
 * we receive a message with a new tick. In this case, the message can be published. The power is computed when
 * the energy method is called. It allows to not overload the messages receiving.
 */
case class LibpfmFormulaMessage(tick: Tick, device: String = "cpu", messages: collection.mutable.Set[LibpfmSensorMessage] = collection.mutable.Set[LibpfmSensorMessage]()) extends FormulaMessage with Configuration {
  def energy = {
    // Get the formula (currently, we take the formulae which corresponds to the max frequency).
    val frequency = formulae.maxBy(_._1)._1
    val formula = formulae(frequency)
    var acc = 0.0

    // We assume the order is the same in each case (events are always sorted).
    for(i <- 0 until formula.size - 1) {
      acc += (messages.filter(_.event.name == events(i)).foldLeft(0: Double) { 
        (accLocal, message) => {
          if(message.tick.subscription.duration !=  Duration.Zero) {
            accLocal + (formula(i) * (message.counter.value / message.tick.subscription.duration.toSeconds))
          }
          else accLocal
        }
      })
    }

    // Add the constant value.
    acc += formula(formula.size - 1)
    // Remove the idle part.
    acc -= idlePower

    Energy.fromPower(acc)
  }

  def add(message: LibpfmSensorMessage) {
    messages += message
  }

  def +=(message: LibpfmSensorMessage) {
    add(message)
  }
}

/**
 * Represent the formula which reacts on LibpfmSensorMessage. A cache is handled because there is one message per event/process for
 * the same tick. So, we have to wait all the messages for the same tick before to pusblish a formula message.
 */
class LibpfmFormula extends Formula with Configuration {
  val cache = collection.mutable.Map[Long, LibpfmFormulaMessage]()

  def messagesToListen = Array(classOf[LibpfmSensorMessage])

  def addToCache(libpfmSensorMessage: LibpfmSensorMessage) {
    cache get libpfmSensorMessage.tick.timestamp match {
      case Some(agg) => agg += libpfmSensorMessage
      case None => {
        val agg = LibpfmFormulaMessage(tick = Tick(libpfmSensorMessage.tick.clockid, TickSubscription(libpfmSensorMessage.tick.subscription.process, libpfmSensorMessage.tick.subscription.duration)), device = "cpu")
        agg += libpfmSensorMessage
        cache += libpfmSensorMessage.tick.timestamp -> agg
      }
    }
  }

  def dropFromCache(timestamp: Long) = {
    cache -= timestamp
  }

  def send(timestamp: Long) = {
    byClocks(timestamp) foreach publish
  }

  def byClocks(timestamp: Long): Iterable[LibpfmFormulaMessage] = {
    val base = cache(timestamp)
    // Group by timestamp (which is represented by one entry in the cache) and clockid
    val messages = for (byMonitoring <- base.messages.groupBy(_.tick.clockid)) yield (LibpfmFormulaMessage(
      tick = Tick(byMonitoring._1, TickSubscription(base.tick.subscription.process, base.tick.subscription.duration), timestamp),
      device = "cpu",
      messages = byMonitoring._2)
    )

    messages
  }

  def process(libpfmSensorMessage: LibpfmSensorMessage) = {
    if (!cache.isEmpty && !cache.contains(libpfmSensorMessage.tick.timestamp)) {
      // Get first timestamp
      val timestamp = cache.minBy(_._1)._1
      send(timestamp)
      dropFromCache(timestamp)
    }
    addToCache(libpfmSensorMessage)
  }

  def acquire = {
    case libpfmSensorMessage: LibpfmSensorMessage => process(libpfmSensorMessage)
  }
}

/**
 * Companion object used to create this given component.
 */
object FormulaLibpfm extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[LibpfmFormula]
}

/**
 * Use to cook the bake.
 */
trait FormulaLibpfm {
  self: fr.inria.powerapi.core.API =>
  configure(FormulaLibpfm)
}