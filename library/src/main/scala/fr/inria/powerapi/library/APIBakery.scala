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
 * Actors factory, used to create an actor inside an actor context (keep the same hierarchy) or get one which is already created
 * TODO: add control for the creation (only one actor for this kind of component), and change it to a class (one per API)
 */
object ActorsFactory {
  def createActor(componentType: Class[_ <: Component], args: Any*)(implicit context: ActorContext): ActorRef = {
    val prop = Props(componentType, args:_*)
    context.actorOf(prop)
  }

  def findActor(path: String)(implicit timeout: Timeout, context: ActorContext): ActorRef = {
    val futureActorRef = context.actorSelection(path).resolveOne()
    Await.result(futureActorRef, timeout.duration)
  }
}

/**
 * Shortcuts to use the API with the Cake Pattern (depedencies injection)
 * @author mcolmant
 */
trait SensorCpuProc {
  self: API =>
  startComponent(classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor])
}

trait FormulaCpuMax {
  self: API =>
  
  startComponent(classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula])
}

trait AggregatorTimestamp {
  self: API =>

  startComponent(classOf[fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator])
}