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
package fr.inria.powerapi.exp.middleware

case class LineToWrite(filename: String, str: String)
class Writer extends akka.actor.Actor with akka.actor.ActorLogging {
  val resources = scala.collection.mutable.HashMap[String, java.io.FileWriter]()

  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LineToWrite])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[LineToWrite])
    resources.foreach {
      case (_, writer) => writer.close()
    }
  }

  def receive() = {
    case line: LineToWrite => process(line)
    case _ => None
  }

  def process(line: LineToWrite) {
    if(!resources.contains(line.filename)) {
      val filewriter = try {
        new java.io.FileWriter(line.filename, true)
      }
      catch {
        case e: java.io.IOException => null;
      }

      if(filewriter != null) {
        resources += (line.filename -> filewriter)
      }
    }

    if(resources.contains(line.filename)) {
      resources(line.filename).write(line.str)
      resources(line.filename).flush()
    }
  }
}

class LibpfmListener extends akka.actor.Actor {
  var timestamp = -1l
  val cache = scala.collection.mutable.HashMap[String, scala.collection.mutable.ListBuffer[fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage]]()

  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage])
    timestamp = -1l
    cache.clear()
  }
  case class Line(messages: List[fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage]) {
    val aggregated = messages.foldLeft(0l)((acc, message) => acc + message.counter.value)
    override def toString() = s"$aggregated\n"
  }

  def addToCache(event: String, sensorMessage: fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage) = {
    cache.get(event) match {
      case Some(buffer) => buffer += sensorMessage
      case None => {
        val buffer = scala.collection.mutable.ListBuffer[fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage]()
        buffer += sensorMessage
        cache += (event -> buffer)
      }
    }
  }

  def receive() = {
    case sensorMessage: fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage => process(sensorMessage)
  }

  def process(sensorMessage: fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage) {
    if(timestamp == -1) timestamp = sensorMessage.tick.timestamp

    if(sensorMessage.tick.timestamp > timestamp) {
      cache.par.foreach {
        case (event, messages) => {
          context.system.eventStream.publish(LineToWrite(s"output-libpfm-$event.dat", Line(messages.toList).toString))
        }
      }
      timestamp = sensorMessage.tick.timestamp
      cache.clear()
    }

    addToCache(sensorMessage.event.name, sensorMessage)
  }
}

class Reporter extends fr.inria.powerapi.reporter.file.FileReporter {
  lazy val outputPspy = scalax.io.Resource.fromFile("output-powerspy.dat")
  lazy val outputPapi = scalax.io.Resource.fromFile("output-powerapi.dat")

  override def process(processedMessage: fr.inria.powerapi.core.ProcessedMessage) {
    val power = processedMessage.energy.power
    val newLine = scalax.io.Line.Terminators.NewLine.sep

    if(processedMessage.device == "cpu") {
      outputPapi.append(s"$power$newLine")
    }

    else if(processedMessage.device == "powerspy") {
      outputPspy.append(s"$power$newLine")
    }
  }
}

case class RowMessage(tick: fr.inria.powerapi.core.Tick, coefficient: Double, device: String, energy: fr.inria.powerapi.core.Energy)
case class AggregatedMessage(tick: fr.inria.powerapi.core.Tick, device: String, messages: collection.mutable.Set[RowMessage] = collection.mutable.Set[RowMessage]()) extends fr.inria.powerapi.core.ProcessedMessage with FormulaeConfiguration {
  def energy = {
    var energy = fr.inria.powerapi.core.Energy.fromPower(0d)

    if(device == "cpu") {
      var power, maxCoefficient = 0d

      for(message <- messages) {
        power += message.energy.power
        maxCoefficient = if(message.coefficient > maxCoefficient) message.coefficient else maxCoefficient
      }
      
      energy = fr.inria.powerapi.core.Energy.fromPower(power + formulae(maxCoefficient)(0))
    }

    else energy = fr.inria.powerapi.core.Energy.fromPower(messages.foldLeft(0: Double) { (acc, message) => acc + message.energy.power })
    
    energy
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
          case _ => agg += RowMessage(formulaMessage.tick, 0d, formulaMessage.device, formulaMessage.energy)
        }
      }
      case None => {
        val agg = AggregatedMessage(tick = fr.inria.powerapi.core.Tick(fr.inria.powerapi.core.TickSubscription(formulaMessage.tick.subscription.clockid, fr.inria.powerapi.core.Process(-1), formulaMessage.tick.subscription.duration)), device = "all")
        formulaMessage match {
          case msg: fr.inria.powerapi.formula.libpfm.LibpfmCoreCyclesFormulaMessage => agg += RowMessage(msg.tick, msg.maxCoefficient, msg.device, msg.energy)
          case _ => agg += RowMessage(formulaMessage.tick, 0d, formulaMessage.device, formulaMessage.energy)
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

object AggregatorDevice extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[DeviceAggregator]
}

trait AggregatorDevice {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorDevice)
} 
