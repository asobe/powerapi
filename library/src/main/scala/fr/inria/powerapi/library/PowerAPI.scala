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



import fr.inria.powerapi.core.{ Ack, ClockMessages, ClockSupervisor, Component, Message, MessagesToListen }
import fr.inria.powerapi.core.{ Process, ProcessedMessage, Reporter, TickSubscription }

import collection.mutable

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}

import akka.actor.{ Actor, ActorContext, ActorLogging, ActorRef, ActorSystem, Props }
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
object PowerAPIMessages {
  case class StartComponent(name: String, componentType: Class[_ <: Component], args: Any*)
  case class StartSubscription(actorRef: ActorRef)
  case class StartMonitoring(processes: Array[Process], frequency: FiniteDuration)
  case class StartReporterComponent(name: String, componentType: Class[_ <: Reporter], args: Any*)
  case class AttachReporterRef(reporterRef: ActorRef)
  case class StartMonitoringRepr(clockid: Long)
  case class StopMonitoringRepr(clockid: Long)

  case object StopMonitoringWorker
  case object AllReportersStopped
  case object MonitoringWorkerStopped
  case object StopAll
  case object PowerAPIStopped
  case object AllMonitoringsStopped
  case object StopAllMonitorings
}

class PowerAPI extends Actor with ActorLogging {
  import PowerAPIMessages._
  import ClockMessages.StopAllClocks

  implicit val timeout = Timeout(5.seconds)
  // Stores the actor references for all the components (excepts the ClockSupervisor).
  val components = new mutable.ArrayBuffer[ActorRef] with mutable.SynchronizedBuffer[ActorRef]
  var clockSupervisor: ActorRef = null
  var monitoringSupervisor: ActorRef = null

  override def preStart(): Unit = {
    clockSupervisor = context.actorOf(Props[ClockSupervisor])
    monitoringSupervisor = context.actorOf(Props(classOf[MonitoringSupervisor], clockSupervisor))
  }

  def receive = LoggingReceive {
    case startComponent: StartComponent => process(startComponent)
    case startSubscription: StartSubscription => process(startSubscription)
    case startMonitoring: StartMonitoring => process(sender, startMonitoring)
    case StopAll => stopAll(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def process(startComponent: StartComponent) {
    /**
     * Starts a component with its class.
     * @param name: name of the component (used to retrieve it from actorSelection).
     * @param componentType: class of the component.
     * @param args: varargs for the component argument.
     */
    def start(name: String, componentType: Class[_ <: Component], args: Any*): ActorRef = {
      ActorsFactory(self, name, componentType, args:_*)
    }

    val actorRef = start(startComponent.name, startComponent.componentType, startComponent.args:_*)
    
    // It is maybe null if the component is a singleton and already started, or component does not exist
    if(actorRef != null) {
      // Also, do the actor's subscription on the event bus.
      process(StartSubscription(actorRef))
    }

    else if(log.isDebugEnabled) log.debug(startComponent.componentType + " is a singleton and already started.")
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

  def process(sender: ActorRef, startMonitoring: StartMonitoring) {
    import ClockMessages.StartClock

    /**
     * Starts the monitoring of an processes array for a given clock frequency.
     * @param processes: processes to monitor.
     * @param frequency: duration period monitoring.
     */
    def start(sender: ActorRef, processes: Array[Process], frequency: FiniteDuration) {
      // Starts a monitoring actor, retrieves the representative of the monitoring and sends it to the API.
      val clockid = Await.result(clockSupervisor ? StartClock(processes, frequency), timeout.duration).asInstanceOf[Long]
      val monitoringRepr = Await.result(monitoringSupervisor ? StartMonitoringRepr(clockid), timeout.duration).asInstanceOf[Monitoring]
      sender ! monitoringRepr
    }

    start(sender, startMonitoring.processes, startMonitoring.frequency)
  }

  def stopAll(sender: ActorRef) {  
    // Stop all the attached components
    components.foreach(component => {
      val messages = Await.result(component ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.unsubscribe(component, message))
      context.stop(component)
      if(log.isDebugEnabled) log.debug("component stopped.")
    })
    components.clear()
    
    Await.result(clockSupervisor ? StopAllClocks, timeout.duration)
    context.stop(clockSupervisor)
    Await.result(monitoringSupervisor ? StopAllMonitorings, timeout.duration)
    context.stop(monitoringSupervisor)
    sender ! PowerAPIStopped
  }

  // def process(stopMonitoring: StopMonitoring) {
  //   import ClockSupervisor.StopTickSub

  //   /**
  //    * Stops the monitoring of the processes array with the given clock frequency.
  //    * @param processes: processes to monitor.
  //    * @param frequency: duration period monitoring.
  //    */
  //   def stop(processes: Array[Process], frequency: FiniteDuration) {
  //     processes.foreach(process => context.system.eventStream.publish(StopTickSub(TickSubscription(process, frequency))))
  //   }

  //   stop(stopMonitoring.processes, stopMonitoring.frequency)
  // }
  // }

  /**
   * Stops all the components (safety).
   * Only used when you specified a duration for the monitoring.
   * @param sender: sender's actor reference
   */
  /*def stopComponents(sender: ActorRef) = {
    components.foreach(actorRef => {
      val messages = Await.result(actorRef ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.unsubscribe(actorRef, message))
      context.stop(actorRef)
      if(log.isDebugEnabled) log.debug("component stopped.")
    })

    components.clear()
    sender ! Ack
  }*/
}

// Monitoring supervisor
// Subscription: ProcessedMessages on the event bus, reacts to dispatch to the right monitoring for the reporting
class MonitoringSupervisor(clockSupervisor: ActorRef) extends Actor with ActorLogging {
  import PowerAPIMessages._
  import ClockMessages.StartClock

  implicit val timeout = Timeout(5.seconds)
  // [clockid, monitoringRef]
  val monitorings = new mutable.HashMap[Long, ActorRef] with mutable.SynchronizedMap[Long, ActorRef]

  def receive = LoggingReceive {
    case startMonitoringRepr: StartMonitoringRepr => startMonitoring(sender, startMonitoringRepr)
    case stopMonitoringRepr: StopMonitoringRepr => stopMonitoring(sender, stopMonitoringRepr)
    case StopAllMonitorings => stopAllMonitorings(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  // Allows to create an actor for a specified monitoring
  def startMonitoring(sender: ActorRef, startMonitoringRepr: StartMonitoringRepr) = {
    // create a monitoring  
    val monitoringRef = context.actorOf(Props(classOf[MonitoringWorker], clockSupervisor))
    monitorings += (startMonitoringRepr.clockid -> monitoringRef)
    sender ! new Monitoring(startMonitoringRepr.clockid, self, monitoringRef)
  }

  // Allows to stop a specified monitoring and stop all these referenced reporters.
  def stopMonitoring(sender: ActorRef, stopMonitoringRepr: StopMonitoringRepr) = {
    val monitoringRef = monitorings(stopMonitoringRepr.clockid)
    monitorings -= stopMonitoringRepr.clockid
    Await.result(monitoringRef ? StopMonitoringWorker, timeout.duration)
    context.stop(monitoringRef)
    sender ! MonitoringWorkerStopped
  }

  def stopAllMonitorings(sender: ActorRef) = {
    monitorings.foreach {
      case (_, monitoringRef) => {
        Await.result(monitoringRef ? StopMonitoringWorker, timeout.duration)
        context.stop(monitoringRef)
      }
    }

    monitorings.clear
    sender ! AllMonitoringsStopped
  }
}

class MonitoringWorker(clockSupervisor: ActorRef) extends Actor with ActorLogging {
  import PowerAPIMessages._
  import ClockMessages.WaitFor

  implicit val timeout = Timeout(5.seconds)
  // Buffer of reporter references, only used to store all of them when the monitoring stop is asked.
  val reporters = new mutable.ArrayBuffer[ActorRef] with mutable.SynchronizedBuffer[ActorRef]

  def receive = LoggingReceive {
    case startReporterComponent: StartReporterComponent => process(startReporterComponent)
    case attachReporterRef: AttachReporterRef => process(attachReporterRef)
    case waitFor: WaitFor => process(sender, waitFor)
    case StopMonitoringWorker => stopMonitoringWorker(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def process(startReporterComponent: StartReporterComponent) {
    /**
     * Starts a reporter with its class.
     * @param componentType: class of the component.
     * @param args: varargs for the component argument.
     */
    def start(actorName: String, componentType: Class[_ <: Component], args: Any*): ActorRef = {
      val prop = Props(componentType, args: _*)

      if(actorName == "") {
        context.actorOf(prop)
      }
      else context.actorOf(prop, name = actorName)
    }

    val reporterRef = start(startReporterComponent.name, startReporterComponent.componentType, startReporterComponent.args:_*)

    process(AttachReporterRef(reporterRef))
  }

  def process(attachReporterRef: AttachReporterRef) {
    /**
     * Starts a reporter with its actor reference
     * @param actorRef: actor reference of the reporter which will be attach to this monitoring
     */
    def start(reporterRef: ActorRef) {
      // Do the subscription on the bus
      val messages = Await.result(reporterRef ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.subscribe(reporterRef, message))
      // Store its reference inside the buffer. Used when the given monitoring is stopped.
      reporters += reporterRef
    }

     start(attachReporterRef.reporterRef)
  }

  def process(sender: ActorRef, waitFor: WaitFor) {
    def runningForDuration(clockid: Long, duration: FiniteDuration): Future[Any] = {
      implicit val timeout = Timeout(duration + 1.seconds)
      clockSupervisor ? WaitFor(clockid, duration)
    }

    val futureAck = runningForDuration(waitFor.clockid, waitFor.duration)
    sender ! futureAck
  }

  /**
   * Stop all the reporters for this given monitoring.
   */
  def stopMonitoringWorker(sender: ActorRef) = {
    reporters.foreach(reporter => context.stop(reporter))
    reporters.clear()
    sender ! AllReportersStopped
  }
}

class Monitoring(clockid: Long, monitoringSupervisor: ActorRef, monitoringRef: ActorRef) {
  import PowerAPIMessages._
  import ClockMessages.WaitFor

  /**
   * Allows to attach a reporter with its component type.
   * @param reporterType: Type of the reporter.
   */
  def attachReporter(reporterType: Class[_ <: Reporter]): Monitoring = {
    monitoringRef ! StartReporterComponent("", reporterType)
    this
  }

  /**
   * Allows to attach a reporter with a function to process the messages display.
   * @param reporterProcessing: Function used by a callback reporter to process the messages.
   */
  def attachReporter(reporterProcessing: (ProcessedMessage => Unit)): Monitoring = {
    monitoringRef ! StartReporterComponent("", classOf[CallbackReporter], reporterProcessing)
    this
  }

  /**
   * Allows to attach a reporter represented by an ActorRef.
   * @param reporterRef: reference of the actor.
   */
  def attachReporter(reporterRef: ActorRef): Monitoring = {
    monitoringRef ! AttachReporterRef(reporterRef)
    this
  }

  def waitFor(duration: FiniteDuration) = {
    implicit val timeout = Timeout(duration + 1.seconds)
    // Wait for the clock ending
    val futureAck = Await.result(monitoringRef ? WaitFor(clockid, duration), timeout.duration).asInstanceOf[Future[Object]]
    Await.result(futureAck, duration + 1.seconds)
    // Now, we can stop this monitoring
    Await.result(monitoringSupervisor ? StopMonitoringRepr(clockid), timeout.duration)
  }
}

/**
 * Main API used as a dependency for each trait component. It's the main entry to
 * interact with the main API actor.
 */
class API {
  import PowerAPIMessages._
  //import ClockSupervisor.Running

  implicit lazy val system = ActorSystem(System.currentTimeMillis + "")
  lazy val engine = system.actorOf(Props[PowerAPI], "powerapi")
  implicit val timeout = Timeout(5.seconds)

  /**
   * Starts the clock supervisor, main component of the API (mandatory).
   */
  //engine ! StartComponent("clock-supervisor", classOf[ClockSupervisor])

  /** 
   * Create a monitoring supervisor, used to create the monitoring representatives
   */

   
  //val clockRef = Await.result(engine ? StartClock, timeout.duration).asInstanceOf[ActorRef]

  // If a monitoring duration is fixed, we asked the ClockSupervisor to stay alive during this period.
  // Timeout fixed because it's needed. We add 5.seconds to handle possible latency problems.
  //val ack = clockRef.ask(Running(duration))(Timeout(duration + 5.seconds))
  
  /**
   * Starts the component associated to the given type.
   * @param componentType: component type to start.
   */
  def configure(componentType: Class[_ <: Component]) {
    engine ! StartComponent("", componentType)
  }

  /**
   * Stops all the components.
   * If you use a duration for the monitoring, you could call this method after ack.
   * Else, you have to stop the monitoring for each process.
   */
  // def stop() {
  //   Await.result(engine ? StopComponents, timeout.duration)
  // }

  // /**
  //  * Allows to attach a reporter with its component type.
  //  * @param reporterType: Type of the reporter.
  //  */
  // def attachReporter(reporterType: Class[_ <: Reporter]) = {
  //   engine ! StartComponent(reporterType)
  // }

  // /**
  //  * Allows to attach a reporter with a function to process the messages display.
  //  * @param reporterProcessing: Function used by a callback reporter to process the messages.
  //  */
  // def attachReporter(reporterProcessing: (ProcessedMessage => Unit)) {
  //   engine ! StartComponent(classOf[CallbackReporter], reporterProcessing)
  // }

  // /**
  //  * Allows to attach a reporter represented by an ActorRef.
  //  * @param reporterRef: reference of the actor.
  //  */
  // def attachReporter(reporterRef: ActorRef) {
  //   engine ! StartSubscription(reporterRef)
  // }

  /**
   * Starts the monitoring of an processes array for a given clock frequency.
   * @param processes: processes to monitor.
   * @param frequency: duration period monitoring.
   */
  def start(processes: Array[Process], frequency: FiniteDuration): Monitoring = {
    Await.result(engine ? StartMonitoring(processes, frequency), timeout.duration).asInstanceOf[Monitoring]
  }

  def stop() = {
    Await.result(engine ? StopAll, timeout.duration)
    system.shutdown()
  }

  // /**
  //  * Stops the monitoring of the processes array with the given clock frequency.
  //  * @param processes: processes to monitor.
  //  * @param frequency: duration period monitoring.
  //  */
  // def stopMonitoring(processes: Array[Process], frequency: FiniteDuration) {
  //   engine ! stopMonitoring(processes, frequency)
  // }
}