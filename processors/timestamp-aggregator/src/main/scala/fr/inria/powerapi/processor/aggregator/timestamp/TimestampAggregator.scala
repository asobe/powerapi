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
package fr.inria.powerapi.processor.aggregator.timestamp

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.FormulaMessage
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.core.Processor
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription

/**
* Messages that can be sent by aggregators:
* 1. RowMessage, wrapper for a FormulaMessage which is replaced by a ProcessedMessage in order to differ from it (by type)
* 2. AggregatedMessage, RowMessage and AggregatedMessage composite
*
* @author abourdon
*/
case class RowMessage(tick: Tick, device: String, energy: Energy) extends ProcessedMessage
case class AggregatedMessage(tick: Tick, device: String, messages: collection.mutable.Set[ProcessedMessage] = collection.mutable.Set[ProcessedMessage]()) extends ProcessedMessage {
  def energy = Energy.fromPower(messages.foldLeft(0: Double) { (acc, message) => acc + message.energy.power })
  def add(message: ProcessedMessage) {
    messages += message
  }
  def +=(message: ProcessedMessage) {
    add(message)
  }
}

/**
* Aggregates FormulaMessages by their clockids (which represent the different monitorings) and timestamp.
*
* By default, TimestampAggregator builds new AggregatedMessage with process = Process(-1) and device = "all".
* Note that Process(-1) means "all processes".
*/
class TimestampAggregator extends Processor {
  // Cache has to be created during the instance creation in order to limit overhead
  // and thus reduce latency when receiving formula messages.
  // [clockid -> [timestamp -> messages]]
  val cache = collection.mutable.Map[Long, collection.mutable.Map[Long, AggregatedMessage]]()

  def addToCache(formulaMessage: FormulaMessage) {
    if(!cache.contains(formulaMessage.tick.subscription.clockid)) cache(formulaMessage.tick.subscription.clockid) = collection.mutable.Map[Long, AggregatedMessage]()

    cache(formulaMessage.tick.subscription.clockid) get formulaMessage.tick.timestamp match {
      case Some(agg) => agg += RowMessage(formulaMessage.tick, formulaMessage.device, formulaMessage.energy)
      case None => {
        val agg = AggregatedMessage(tick = Tick(TickSubscription(formulaMessage.tick.subscription.clockid, Process(-1), formulaMessage.tick.subscription.duration)), device = "all")
        agg += RowMessage(formulaMessage.tick, formulaMessage.device, formulaMessage.energy)
        cache(formulaMessage.tick.subscription.clockid) += formulaMessage.tick.timestamp -> agg
      }
    }
  }

  // clockid, timestamp
  def dropFromCache(implicit args: List[Long]) {
    cache(args(0)) -= args(1)
  }

  def send(implicit args: List[Long]) {
    publish(cache(args(0))(args(1)))
  }

  def process(formulaMessage: FormulaMessage) {
    if (!cache.isEmpty && cache.contains(formulaMessage.tick.subscription.clockid) && !cache(formulaMessage.tick.subscription.clockid).contains(formulaMessage.tick.timestamp)) {
      // Get first timestamp to inject it in each method
      implicit val args: List[Long] = List(formulaMessage.tick.subscription.clockid, cache(formulaMessage.tick.subscription.clockid).minBy(_._1)._1)
      send
      dropFromCache
    }
    addToCache(formulaMessage)
  }
}

/**
 * Companion object used to create this given component.
 */
object AggregatorTimestamp extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[TimestampAggregator]
}

/**
 * Use to cook the bake.
 */
trait AggregatorTimestamp {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorTimestamp)
}
