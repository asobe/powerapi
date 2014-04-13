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
package fr.inria.powerapi.core

import akka.event.LoggingReceive
import akka.actor.{ Actor, ActorContext, ActorLogging, ActorRef, Props }

/**
 * Base types used to describe PowerAPI architecture.
 *
 * PowerAPI is based on a modular and asynchronous architecture
 * where modules are centralized into a common event bus.
 * Each module communicates with others through immutable messages in using
 * the Akka library.
 *
 * @see http://akka.io
 *
 * @author abourdon
 */

/**
 * Request to have the array of Messages that a Component have to listen.
 */
object MessagesToListen extends Message

/**
 * Base trait for the components which listen on the event bus, also called Component.
 */
trait Component extends akka.actor.Actor with ActorLogging {
  /**
   * Akka's receive() wrapper.
   *
   * @see http://doc.akka.io/docs/akka/snapshot/scala/actors.html
   */
  def acquire: Receive

  /**
   * Defines what kind of Message this component wants to be aware
   * from the common event bus.
   */
  def messagesToListen: Array[Class[_ <: Message]]

  private lazy val messages = messagesToListen

  private def listenToMessages: Receive = {
    case MessagesToListen => sender ! messages
  }

  def receive = LoggingReceive {
    listenToMessages orElse acquire
  }

  /**
   * Publishes the given message to the common event bus.
   *
   * @param message: the message to publish to the common event bus.
   */
  def publish(message: Message) {
    context.system.eventStream publish message
  }
}

/**
 * Base trait for each PowerAPI sensor.
 *
 * Each of them should listen to a Tick message and so process it.
 */
trait Sensor extends Component {
  def messagesToListen = Array(classOf[Tick])

  def process(tick: Tick)

  def acquire = {
    case tick: Tick => process(tick)
  }
}

/**
 * Base trait for each PowerAPI formula.
 * 
 */
trait Formula extends Component

/**
 * Base trait for each PowerAPI processor.
 *
 * Each of them should listen to FormulaMessage message and so process it.
 */
trait Processor extends Component {
  def messagesToListen = Array(classOf[FormulaMessage])

  def process(formulaMessage: FormulaMessage)

  def acquire = {
    case formulaMessage: FormulaMessage => process(formulaMessage)
  }
}

/**
 * Base trait for each PowerAPI reporter.
 */
trait Reporter extends Actor with ActorLogging {
  def process(processedMessage: ProcessedMessage)

  def receive = LoggingReceive {
    case processedMessage: ProcessedMessage => process(processedMessage)
    case unknown => throw new UnsupportedOperationException("unable to process message" + unknown)
  }
}

/**
 * Trait which acts like a factory, used by the companion of each component.
 */
trait APIComponent {
  val apisRegistered = new collection.mutable.ArrayBuffer[ActorRef] with collection.mutable.SynchronizedBuffer[ActorRef]

  // Allows to define if the component is a singleton or not.
  def singleton: Boolean
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
 * There is at least a configure method for an API.
 */
trait API {
  /**
   * Starts the component associated to the given type.
   * @param componentType: component type to start.
   */
  def configure[U <: APIComponent](companion: U): Unit
}