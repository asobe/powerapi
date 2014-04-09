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
 * Trait which acts like a factory, each component can create it itself with the apply method.
 */
trait APIComponent {
  val apisRegistered = new collection.mutable.ArrayBuffer[ActorRef] with collection.mutable.SynchronizedBuffer[ActorRef]

  // By default, a component is a singleton.
  def singleton: Boolean = {
    true
  }

  // Underlying class of a module component, used to create the actor.
  def underlyingClass: Class[_ <: Component]
  // List of the arguments passed to the actor when it's created (default, no parameters).
  def args: List[Any] = List()

  /**
   * Creates the actor (attached to the api) with the implicit actor context (which comes from an Actor)
   * @param api: Reference of the api, used to check for possibility to have several instances.
   */
  def apply(api: ActorRef)(implicit context: ActorContext): Option[ActorRef] = {
    if(!apisRegistered.contains(api) || !singleton) {
      val prop = Props(underlyingClass, args: _*)
      val actorRef = context.actorOf(prop)
      if(!apisRegistered.contains(api)) apisRegistered += api
      Some(actorRef)
    }

    else None
  }
}

/**
 * Objects use to map PowerAPI and Bakery components.
 */
object SensorCpuProc extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.sensor.cpu.proc.CpuSensor]
}

object SensorPowerspy extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.sensor.powerspy.PowerSpySensor]
}

object FormulaCpuMax extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.formula.cpu.max.CpuFormula]
}

object FormulaPowerspy extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.formula.powerspy.PowerSpyFormula]
}

object AggregatorTimestamp extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator]
}

object AggregatorProcess extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.processor.aggregator.process.ProcessAggregator]
}

object AggregatorDevice extends APIComponent {
  val underlyingClass = classOf[fr.inria.powerapi.processor.aggregator.device.DeviceAggregator]
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

trait AggregatorProcess {
  self: API =>
  configure(AggregatorProcess)
}

trait AggregatorDevice {
  self: API =>
  configure(AggregatorDevice)
}