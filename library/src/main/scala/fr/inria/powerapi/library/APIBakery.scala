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
package fr.inria.powerapi.library

import fr.inria.powerapi.core.{Component, Reporter, ProcessedMessage}

import akka.actor.{ ActorContext, ActorRef, Props }
import scala.concurrent.Await
import akka.util.Timeout
import scala.collection


/**
 * Component factories + main factory (used in the API, shortcuts)
 * TODO: Move the code
 */
object ActorsFactory {
  def apply(api: ActorRef, name: String, componentType: Class[_ <: Any], args: Any*)(implicit context: ActorContext): ActorRef = {
    componentType match {
      case _ if componentType == classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor] => SensorCpuProc(api, name, args: _*)
      case _ if componentType == classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula] => FormulaCpuMax(api, name, args: _*)
      case _ if componentType == classOf[fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator] => AggregatorTimestamp(api, name, args: _*)
      case _ => throw new UnsupportedOperationException("component non identified.")
    }
  }
}

object SensorCpuProc {
  val singleton = true
  val references = collection.mutable.ArrayBuffer.empty[ActorRef]

  def apply(api: ActorRef, actorName: String, args: Any*)(implicit context: ActorContext): ActorRef = {
    if(!references.contains(api) || !singleton) {
      val prop = Props(classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor], args: _*)
      
      val actorRef = if(actorName == "") {
        context.actorOf(prop)
      }
      else context.actorOf(prop, name = actorName)

      references += api
      return actorRef
    }

    null
  }
}

object FormulaCpuMax {
  val singleton = true
  val references = collection.mutable.ArrayBuffer.empty[ActorRef]

  def apply(api: ActorRef, actorName: String, args: Any*)(implicit context: ActorContext): ActorRef = {
    if(!references.contains(api) || !singleton) {
      val prop = Props(classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula], args: _*)

      val actorRef = if(actorName == "") {
        context.actorOf(prop)
      }
      else context.actorOf(prop, name = actorName)

      references += api
      return actorRef
    }

    null
  }
}

object AggregatorTimestamp {
  val singleton = true
  val references = collection.mutable.ArrayBuffer.empty[ActorRef]

  def apply(api: ActorRef, actorName: String, args: Any*)(implicit context: ActorContext): ActorRef = {
    if(!references.contains(api) || !singleton) {
      val prop = Props(classOf[fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator], args: _*)

      val actorRef = if(actorName == "") {
        context.actorOf(prop)
      }
      else context.actorOf(prop, name = actorName)

      references += api
      return actorRef
    }

    null
  }
}

/**
 * Shortcuts to use the API with the Cake Pattern (depedencies injection)
 * @author mcolmant
 */
trait SensorCpuProc {
  self: API =>
  configure(classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor])
}

trait FormulaCpuMax {
  self: API =>
  configure(classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula])
}

trait AggregatorTimestamp {
  self: API =>
  configure(classOf[fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator])
}