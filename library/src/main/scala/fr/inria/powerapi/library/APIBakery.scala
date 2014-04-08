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

import fr.inria.powerapi.core.Component

import akka.actor.{ ActorContext, ActorRef, Props }
import scala.collection

/**
 * Represents a component inside the bakery, used like a factory.
 */
trait BakeryComponent {
  lazy val references = collection.mutable.ArrayBuffer.empty[ActorRef]

  // By default, a component is a singleton.
  def singleton: Boolean = {
    true
  }

  // Underlying class, used to create the actor.
  def underlyingClass: Class[_ <: Component]
  // List of the arguments passed to the actor when it's created (default, no parameters).
  def args: List[Any] = List()

  /**
   * Creates the actor (attached to the api) with the implicit actor context (which comes from an Actor)
   * @param api: Reference of the api, used to check for possibility to have several instances.
   */
  def apply(api: ActorRef)(implicit context: ActorContext): Option[ActorRef] = {
    if(!references.contains(api) || !singleton) {
      val prop = Props(underlyingClass, args: _*)
      val actorRef = context.actorOf(prop)
      references += api
      Some(actorRef)
    }

    else None
  }
}

/**
 * Objects use to map PowerAPI and Bakery components.
 */
object SensorCpuProc extends BakeryComponent {
  val underlyingClass = classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor]
}

object SensorPowerspy extends BakeryComponent {
  val underlyingClass = classOf[fr.inria.powerapi.sensor.powerspy.PowerSpySensor]
}

object FormulaCpuMax extends BakeryComponent {
  val underlyingClass = classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula]
}

object FormulaPowerspy extends BakeryComponent {
  val underlyingClass = classOf[fr.inria.powerapi.formula.powerspy.PowerSpyFormula]
}

object AggregatorTimestamp extends BakeryComponent {
  val underlyingClass = classOf[fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator]
}

/**
 * Shortcuts to use the API with the Cake Pattern.
 */
trait SensorCpuProc {
  self: API =>
  configure(SensorCpuProc)
}

trait SensorPowerspy {
  self: API =>
  configure(SensorPowerspy)
}

trait FormulaCpuMax {
  self: API =>
  configure(FormulaCpuMax)
}

trait FormulaPowerspy {
  self: API =>
  configure(FormulaPowerspy)
}

trait AggregatorTimestamp {
  self: API =>
  configure(AggregatorTimestamp)
}