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



import fr.inria.powerapi.core.{ Ack, ClockSupervisor, Component, Message, MessagesToListen }
import fr.inria.powerapi.core.{ Process, ProcessedMessage, Reporter, TickSubscription }

import collection.mutable

import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout

/**
 * Default reporter used to process the messages with a callbacK.
 */
class CallbackReporter(callback: (ProcessedMessage) => Unit)  extends Reporter {
  def process(processedMessage: ProcessedMessage) {
    callback(processedMessage)
  }
}

/**
 * PowerAPI object encapsulates all the messages.
 */
object PowerAPIv3 {
  case class StartClock(componentType: Class[_ <: Component])
  case class StartComponent(componentType: Class[_ <: Component], args: Any*)
  case object StopComponents
  case class StartSubscription(actorRef: ActorRef)
  case class StartMonitoring(processes: Array[Process], frequency: FiniteDuration)
  case class StopMonitoring(processes: Array[Process], frequency: FiniteDuration)
}

class PowerAPIv3 extends Actor with ActorLogging {
  import PowerAPIv3._

  implicit val timeout = Timeout(5.seconds)
  // Stores the actor references for all the components (excepts the ClockSupervisor).
  val components = new mutable.ArrayBuffer[ActorRef] with mutable.SynchronizedBuffer[ActorRef]

  def receive = LoggingReceive {
    case startClock: StartClock => process(startClock, sender)
    case startComponent: StartComponent => process(startComponent)
    case StopComponents => stopComponents(sender)
    case startSubscription: StartSubscription => process(startSubscription)
    case startMonitoring: StartMonitoring => process(startMonitoring)
    case stopMonitoring: StopMonitoring => process(stopMonitoring)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def process(startClock: StartClock, sender: ActorRef) {
    val actorRef = process(StartComponent(startClock.componentType))
    // Sends the actor reference to interact directly with the ClockSupervisor.
    sender ! actorRef
  }

  def process(startComponent: StartComponent): ActorRef = {
    /**
     * Starts a component with its class.
     * @param componentType: class of the component.
     * @param args: varargs for the component argument.
     */
    def start(componentType: Class[_ <: Component], args: Any*): ActorRef = {
      ActorsFactory.createActor(componentType, args:_*)
    }

    val actorRef = start(startComponent.componentType, startComponent.args:_*)
    // Also, do the actor's subscription on the event bus.
    process(StartSubscription(actorRef))
    // Returns the actor reference, maybe not used.
    actorRef
  }

  def process(startSubscription: StartSubscription) {
    /**
     * Subscription of the actor on the event bus and register the actor reference inside a buffer.
     * to stop them when the monitoring will be done.
     * @param actorRef: Reference of the given actor.
     */
    def subscribe(actorRef: ActorRef) {
      val messages = Await.result(actorRef ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.subscribe(actorRef, message))
      components += actorRef
    }

    subscribe(startSubscription.actorRef)
  }

  def process(startMonitoring: StartMonitoring) {
    import ClockSupervisor.StartTickSub

    /**
     * Starts the monitoring of an processes array for a given clock frequency.
     * @param processes: processes to monitor.
     * @param frequency: duration period monitoring.
     */
    def start(processes: Array[Process], frequency: FiniteDuration) {
      processes.foreach(process => context.system.eventStream.publish(StartTickSub(TickSubscription(process, frequency))))
    }

    start(startMonitoring.processes, startMonitoring.frequency)
  }

  def process(stopMonitoring: StopMonitoring) {
    import ClockSupervisor.StopTickSub

    /**
     * Stops the monitoring of the processes array with the given clock frequency.
     * @param processes: processes to monitor.
     * @param frequency: duration period monitoring.
     */
    def stop(processes: Array[Process], frequency: FiniteDuration) {
      processes.foreach(process => context.system.eventStream.publish(StopTickSub(TickSubscription(process, frequency))))
    }

    stop(stopMonitoring.processes, stopMonitoring.frequency)
  }

  /**
   * Stops all the components (safety).
   * Only used when you specified a duration for the monitoring.
   * @param sender: sender's actor reference
   */
  def stopComponents(sender: ActorRef) = {
    components.foreach(actorRef => {
      val messages = Await.result(actorRef ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.unsubscribe(actorRef, message))
      context.stop(actorRef)
      if(log.isDebugEnabled) log.debug("component stopped.")
    })

    components.clear()
    sender ! Ack
  }
}

/**
 * Main API used as a dependency for each trait component. It's the main entry to
 * interact with the main API actor.
 */
class API(name: String, duration: FiniteDuration = Duration.Zero) {
  import PowerAPIv3._
  import ClockSupervisor.Running

  implicit lazy val system = ActorSystem(name)
  lazy val engine = system.actorOf(Props[PowerAPIv3])
  implicit val timeout = Timeout(5.seconds)

  /**
   * Starts the clock supervisor, main component of the API (mandatory).
   * Waiting for its reference.
   */
  val clockRef = Await.result(engine ? StartClock(classOf[ClockSupervisor]), timeout.duration).asInstanceOf[ActorRef]

  // If a monitoring duration is fixed, we asked the ClockSupervisor to stay alive during this period.
  // Timeout fixed because it's needed. We add 5.seconds to handle possible latency problems.
  val ack = clockRef.ask(Running(duration))(Timeout(duration + 5.seconds))
  
  /**
   * Starts the component associated to the given type.
   * @param componentType: component type to start.
   */
  def startComponent(componentType: Class[_ <: Component]) {
    engine ! StartComponent(componentType)
  }

  /**
   * Stops all the components.
   * If you use a duration for the monitoring, you could call this method after ack.
   * Else, you have to stop the monitoring for each process.
   */
  def stop() {
    Await.result(engine ? StopComponents, timeout.duration)
  }

  /**
   * Allows to attach a reporter with its component type.
   * @param reporterType: Type of the reporter.
   */
  def attachReporter(reporterType: Class[_ <: Reporter]) {
    engine ! StartComponent(reporterType)
  }

  /**
   * Allows to attach a reporter with a function to process the messages display.
   * @param reporterProcessing: Function used by a callback reporter to process the messages.
   */
  def attachReporter(reporterProcessing: (ProcessedMessage => Unit)) {
    engine ! StartComponent(classOf[CallbackReporter], reporterProcessing)
  }

  /**
   * Allows to attach a reporter represented by an ActorRef.
   * @param reporterRef: reference of the actor.
   */
  def attachReporter(reporterRef: ActorRef) {
    engine ! StartSubscription(reporterRef)
  }

  /**
   * Starts the monitoring of an processes array for a given clock frequency.
   * @param processes: processes to monitor.
   * @param frequency: duration period monitoring.
   */
  def startMonitoring(processes: Array[Process], frequency: FiniteDuration) {
    engine ! StartMonitoring(processes, frequency)
  }

  /**
   * Stops the monitoring of the processes array with the given clock frequency.
   * @param processes: processes to monitor.
   * @param frequency: duration period monitoring.
   */
  def stopMonitoring(processes: Array[Process], frequency: FiniteDuration) {
    engine ! stopMonitoring(processes, frequency)
  }
}