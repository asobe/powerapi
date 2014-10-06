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
package fr.inria.powerapi.tool.powerapi

case class RowMessage(tick: fr.inria.powerapi.core.Tick, coefficient: Double, device: String, energy: fr.inria.powerapi.core.Energy)
case class AggregatedMessage(tick: fr.inria.powerapi.core.Tick, device: String, messages: collection.mutable.Set[RowMessage] = collection.mutable.Set[RowMessage]()) extends fr.inria.powerapi.core.ProcessedMessage with fr.inria.powerapi.formula.libpfm.LibpfmCoreCyclesFormulaConfiguration {
  def energy = {
    if(device == "cpu") {
      var maxCoefficient = 0d

      for(message <- messages) {
        maxCoefficient = math.max(message.coefficient, maxCoefficient)
      }
      val idle = formulae(maxCoefficient)(0)
      scalax.io.Resource.fromFile("output-idle-formula.dat").append(s"$idle\n")
    }

    fr.inria.powerapi.core.Energy.fromPower(messages.foldLeft(0: Double) { (acc, message) => acc + message.energy.power })
  }

  def add(message: RowMessage) {
    messages += message
  }
  def +=(message: RowMessage) {
    add(message)
  }
}

class TimestampAggregator extends fr.inria.powerapi.core.Processor {
  // [clockid -> [timestamp -> messages]]
  val cache = collection.mutable.Map[Long, collection.mutable.Map[Long, AggregatedMessage]]()

  def addToCache(formulaMessage: fr.inria.powerapi.core.FormulaMessage) {
    if(!cache.contains(formulaMessage.tick.subscription.clockid)) cache(formulaMessage.tick.subscription.clockid) = collection.mutable.Map[Long, AggregatedMessage]()

    cache(formulaMessage.tick.subscription.clockid) get formulaMessage.tick.timestamp match {
      case Some(agg) => {
        formulaMessage match {
          case msg: fr.inria.powerapi.formula.libpfm.LibpfmCoreCyclesFormulaMessage => agg += RowMessage(msg.tick, msg.maxCoefficient, msg.device, msg.energy)
          case _ => agg += RowMessage(formulaMessage.tick, -1d, formulaMessage.device, formulaMessage.energy)
        }
      }
      case None => {
        val agg = AggregatedMessage(tick = fr.inria.powerapi.core.Tick(fr.inria.powerapi.core.TickSubscription(formulaMessage.tick.subscription.clockid, fr.inria.powerapi.core.Process(-1), formulaMessage.tick.subscription.duration)), device = "all")
        formulaMessage match {
          case msg: fr.inria.powerapi.formula.libpfm.LibpfmCoreCyclesFormulaMessage => agg += RowMessage(msg.tick, msg.maxCoefficient, msg.device, msg.energy)
          case _ => agg += RowMessage(formulaMessage.tick, -1d, formulaMessage.device, formulaMessage.energy)
        }

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

  def process(formulaMessage: fr.inria.powerapi.core.FormulaMessage) {
    if (!cache.isEmpty && cache.contains(formulaMessage.tick.subscription.clockid) && !cache(formulaMessage.tick.subscription.clockid).contains(formulaMessage.tick.timestamp)) {
      // Get first timestamp to inject it in each method
      implicit val args: List[Long] = List(formulaMessage.tick.subscription.clockid, cache(formulaMessage.tick.subscription.clockid).minBy(_._1)._1)
      send
      dropFromCache
    }
    addToCache(formulaMessage)
  }
}

class DeviceAggregator extends TimestampAggregator {
  // clockid, timestamp
  def byDevices(implicit args: List[Long]): Iterable[AggregatedMessage] = {
    val base = cache(args(0))(args(1))

    for (byDevice <- base.messages.groupBy(_.device)) yield (AggregatedMessage(
      tick = fr.inria.powerapi.core.Tick(fr.inria.powerapi.core.TickSubscription(args(0), fr.inria.powerapi.core.Process(-1), duration = base.tick.subscription.duration), args(1)),
      device = byDevice._1,
      messages = byDevice._2)
    )
  }

  override def send(implicit args: List[Long]) {
    byDevices foreach publish
  }
}

class ProcessAggregator extends TimestampAggregator {
  // clockid, timestamp
  def byProcesses(implicit args: List[Long]): Iterable[AggregatedMessage] = {
    val base = cache(args(0))(args(1))
    
    for (byProcess <- base.messages.groupBy(_.tick.subscription.process)) yield (AggregatedMessage(
      tick = fr.inria.powerapi.core.Tick(fr.inria.powerapi.core.TickSubscription(args(0), byProcess._1, base.tick.subscription.duration), args(1)),
      device = "cpu",
      messages = byProcess._2)
    )
  }

  override def send(implicit args: List[Long]) {
    byProcesses foreach publish
  }
}

object AggregatorTimestamp extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[TimestampAggregator]
}

trait AggregatorTimestamp {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorTimestamp)
}

object AggregatorDevice extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[DeviceAggregator]
}

trait AggregatorDevice {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorDevice)
} 

object AggregatorProcess extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[ProcessAggregator]
}

trait AggregatorProcess {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorProcess)
}
