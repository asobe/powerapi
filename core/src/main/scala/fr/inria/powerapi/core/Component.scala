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
import akka.actor.Props

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
 * Base trait for each PowerAPI module, also called Component.
 */
trait Component extends akka.actor.Actor with akka.actor.ActorLogging {
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
 * Base trait for each PowerAPI energy module,
 * typically composed by a sensor and a formula.
 */
trait EnergyModule extends Component

/**
 * Base trait for each PowerAPI sensor.
 *
 * Each of them should listen to a Tick message and so process it.
 */
trait Sensor extends EnergyModule {
  def messagesToListen = Array(classOf[Tick])

  def process(tick: Tick)

  def acquire = {
    case tick: Tick => process(tick)
  }
}

/**
 * Base trait for each PowerAPI formula.
 */
trait Formula extends EnergyModule

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
 * Base trait for each PowerAPI listener.
 *
 * TODO: stop using the Listener type in favor of the Reporter type which listen to Processor's messages.
 */
trait Listener extends Component

/**
 * Base trait for each PowerAPI reporter.
 *
 * TODO: stop Listener inheritance (now kept for compatibility issues).
 */
trait Reporter extends Listener {
  def messagesToListen = Array(classOf[ProcessedMessage])

  def process(processedMessage: ProcessedMessage)

  def acquire = {
    case processedMessage: ProcessedMessage => process(processedMessage)
  }
}