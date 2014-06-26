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

import fr.inria.powerapi.core.{ ClockMessages, ClockSupervisor, Component, Message, MessagesToListen }
import fr.inria.powerapi.core.{ Process, ProcessedMessage, Reporter, TickSubscription }

import collection.mutable

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}

import akka.actor.{ Actor, ActorContext, ActorLogging, ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout

/**
 * Encapsulates the monitoring messages.
 */
object MonitoringMessages {
  case class StartMonitoringRepr(clockid: Long)
  case class StopMonitoringRepr(clockid: Long)
  case class StartReporterComponent(reporterType: Class[_ <: Reporter], args: Any*)
  case class AttachReporterRef(reporterRef: ActorRef)
  case class AttachProcess(clockid: Long, process: Process)
  case class DetachProcess(clockid: Long, process: Process)

  case object AllMonitoringsStopped
  case object StopMonitoringWorker
  case object AllReportersStopped
  case object MonitoringWorkerStopped
}

/**
 * Represents the supervisor for the monitoring. This class is responsible to handle all the monitorings
 * It reacts to the ProcessedMessages published on the event bus and dispatches them to the right monitoring 
 * in accordance with the clockid (one clock per monitoring)
 */
class MonitoringSupervisor(clockSupervisor: ActorRef) extends Actor with ActorLogging {
  import MonitoringMessages._
  import ClockMessages.StartClock
  import PowerAPIMessages.StopAllMonitorings

  implicit val timeout = Timeout(5.seconds)
  // [clockid, monitoringRef]
  val monitorings = new mutable.HashMap[Long, ActorRef] with mutable.SynchronizedMap[Long, ActorRef]

  // Reacts to the ProcessedMessages published by Processors.
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[ProcessedMessage])
  }

  def receive = LoggingReceive {
    case startMonitoringRepr: StartMonitoringRepr => startMonitoring(sender, startMonitoringRepr)
    case stopMonitoringRepr: StopMonitoringRepr => stopMonitoring(sender, stopMonitoringRepr)
    case processedMessage: ProcessedMessage => forwardProcessedMessage(processedMessage)
    case StopAllMonitorings => stopAllMonitorings(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  /**
   * Starts a monitoring, i.e, an actor which represents it. Also, an object to interact with is created.
   * @param sender: Actor reference of the sender, used to send the representative monitoring object.
   * @param startMonitoringRepr: Case class which contains the clockid (represents the given monitoring).
   */
  def startMonitoring(sender: ActorRef, startMonitoringRepr: StartMonitoringRepr) = { 
    val monitoringRef = context.actorOf(Props(classOf[MonitoringWorker], clockSupervisor))
    monitorings += (startMonitoringRepr.clockid -> monitoringRef)
    sender ! new Monitoring(startMonitoringRepr.clockid, self, monitoringRef)
  }

  /**
   * Stops correctly a specific monitoring.
   * @param sender: Actor reference of the sender, used to send an ack when the monitoring is stopped.
   * @param stopMonitoringRepr: Case class which contains the clockid (represents the given monitoring).
   */
  def stopMonitoring(sender: ActorRef, stopMonitoringRepr: StopMonitoringRepr) = {
    val monitoringRef = monitorings(stopMonitoringRepr.clockid)
    
    if(monitorings.contains(stopMonitoringRepr.clockid)) {
      monitorings -= stopMonitoringRepr.clockid
      Await.result(monitoringRef ? StopMonitoringWorker, timeout.duration)
      context.stop(monitoringRef)
    }
    
    sender ! MonitoringWorkerStopped
  }

  /**
   * Allows to forward the ProcessedMessages (sent by Processors) to the right monitoring.
   * @param processedMessage: AggregatedMessage sent by the processors.
   */
  def forwardProcessedMessage(processedMessage: ProcessedMessage) = {
    val clockid = processedMessage.tick.clockid
    
    if(monitorings.contains(clockid)) {
      val monitoringRef = monitorings(clockid)
      monitoringRef ! processedMessage
    }
  }

  /**
   * Allows to stop all the monitorings handled by the supervisor.
   * @param sender: Actor reference of the sender, used to send an ack when all the monitorings are stopped.
   */
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

/**
 * This class is used to represent a specific monitoring and allows to interact with the underlying actor.
 */
class MonitoringWorker(clockSupervisor: ActorRef) extends Actor with ActorLogging {
  import MonitoringMessages._
  import ClockMessages.{ ListProcesses, StartTick, StopTick, WaitFor }

  implicit val timeout = Timeout(5.seconds)
  // Buffer of reporter references.
  val reporters = new mutable.ArrayBuffer[ActorRef] with mutable.SynchronizedBuffer[ActorRef]

  def receive = LoggingReceive {
    case startReporterComponent: StartReporterComponent => process(startReporterComponent)
    case attachReporterRef: AttachReporterRef => process(attachReporterRef)
    case processedMessage: ProcessedMessage => process(processedMessage)
    case attachProcess: AttachProcess => process(attachProcess)
    case detachProcess: DetachProcess => process(detachProcess)
    case listProcesses: ListProcesses => process(sender, listProcesses)
    case waitFor: WaitFor => process(sender, waitFor)
    case StopMonitoringWorker => stopMonitoringWorker(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  def process(startReporterComponent: StartReporterComponent) {
    /**
     * Starts a reporter with its class.
     * @param reporterType: class of the component.
     * @param args: varargs for the component arguments.
     */
    def start(reporterType: Class[_ <: Reporter], args: Any*): ActorRef = {
      val prop = Props(reporterType, args: _*)
      context.actorOf(prop)
    }

    val reporterRef = start(startReporterComponent.reporterType, startReporterComponent.args:_*)

    process(AttachReporterRef(reporterRef))
  }

  def process(attachReporterRef: AttachReporterRef) {
    /**
     * Attaches a reporter to this monitoring with its actor reference.
     * @param reporterRef: actor reference of the reporter which will be attached.
     */
    def start(reporterRef: ActorRef) {
      reporters += reporterRef
    }

     start(attachReporterRef.reporterRef)
  }

  /**
   * Forwards the ProcessedMessage message to all the reporters attached to this monitoring
   * @param processedMessage: AggregatedMessage sent by the processors.
   */
  def process(processedMessage: ProcessedMessage) = {
    reporters.foreach(reporterRef => reporterRef ! processedMessage)
  }

  /**
   * Used to monitor a new process, dynamically.
   * @param process: Process to attach.
   */
  def process(attachProcess: AttachProcess) = {
    val clockid = attachProcess.clockid
    val process = attachProcess.process
    clockSupervisor ! StartTick(clockid, process)
  }

  /**
   * Used to detach dynamically a process.
   * @param process: Process to detach.
   */
  def process(detachProcess: DetachProcess) = {
    val clockid = detachProcess.clockid
    val process = detachProcess.process
    clockSupervisor ! StopTick(clockid, process)
  }

  /**
   * Allows to get the processes which are monitored by this monitoring.
   * @param sender: actor reference of the sender.
   * @param listProcesses: case class used to get the list of processes which are monitored.
   */
  def process(sender: ActorRef, listProcesses: ListProcesses) = {
    val clockid = listProcesses.clockid
    val futureProcessesList = Await.result(clockSupervisor ? listProcesses, timeout.duration).asInstanceOf[Future[List[Process]]]
    sender ! futureProcessesList
  }

  def process(sender: ActorRef, waitFor: WaitFor) {
    /**
     * Allows to block a monitoring during the given duration.
     * @param clockid: clock id for this monitoring.
     * @param duration: runtime duration for this monitoring.
     */
    def runningForDuration(clockid: Long, duration: FiniteDuration): Future[Any] = {
      implicit val timeout = Timeout(duration + 1.seconds)
      clockSupervisor ? WaitFor(clockid, duration)
    }

    // Forwards the ack, we will stop the execution later.
    val futureAck = runningForDuration(waitFor.clockid, waitFor.duration)
    sender ! futureAck
  }

  /**
   * Stops all the reporters for this given monitoring and sends an ack.
   */
  def stopMonitoringWorker(sender: ActorRef) = {
    reporters.foreach(reporter => context.stop(reporter))
    reporters.clear()
    sender ! AllReportersStopped
  }
}

/**
 * Representative of a monitoring. Used to interact with the associated actor.
 */
class Monitoring(clockid: Long, monitoringSupervisor: ActorRef, monitoringRef: ActorRef) {
  import MonitoringMessages._
  import ClockMessages.{ ListProcesses, WaitFor }

  /**
   * Get the reference of the underlying actor.
   */
  def actorRef = {
    monitoringRef
  }

  /**
   * Allows to attach a reporter with its component type.
   * @param reporterType: Type of the reporter.
   */
  def attachReporter(reporterType: Class[_ <: Reporter], args: Any*): Monitoring = {
    monitoringRef ! StartReporterComponent(reporterType, args: _*)
    this
  }

  /**
   * Allows to attach a reporter with a function to process the messages display.
   * @param reporterProcessing: Function used by a callback reporter to process the messages.
   */
  def attachReporter(reporterProcessing: (ProcessedMessage => Unit)): Monitoring = {
    monitoringRef ! StartReporterComponent(classOf[CallbackReporter], reporterProcessing)
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

  /**
   * Used to monitor a new process, dynamically.
   * @param process: Process to attach.
   */
  def attachProcess(process: Process): Monitoring = {
    monitoringRef ! AttachProcess(clockid, process)
    this
  }

  /**
   * Used to detach dynamically a process.
   * @param process: Process to detach.
   */
  def detachProcess(process: Process): Monitoring = {
    monitoringRef ! DetachProcess(clockid, process)
    this
  }

 /**
  * Allows to get the processes which are monitored by this monitoring
  */
  def getMonitoredProcesses(): List[Process] = {
    implicit val timeout = Timeout(5.seconds)
    val futureProcessesList = Await.result(monitoringRef ? ListProcesses(clockid), timeout.duration).asInstanceOf[Future[List[Process]]]
    Await.result(futureProcessesList, timeout.duration).asInstanceOf[List[Process]]
  }

  /**
   * Allows to block the monitoring during a given duration.
   * @param duration: running time.
   */
  def waitFor(duration: FiniteDuration) = {
    implicit val timeout = Timeout(duration + 1.seconds)
    // Wait for the clock ending
    val futureAck = Await.result(monitoringRef ? WaitFor(clockid, duration), timeout.duration).asInstanceOf[Future[Object]]
    Await.result(futureAck, duration + 1.seconds)
    // Now, we can stop this monitoring
    Await.result(monitoringSupervisor ? StopMonitoringRepr(clockid), timeout.duration)
  }

  /**
   * This method allows to stop the given monitoring. It could be useful when the waitFor method is not used.
   */
  def stop() = {
    implicit val timeout = Timeout(5.seconds)
    Await.result(monitoringSupervisor ? StopMonitoringRepr(clockid), timeout.duration)
  }
}

/**
 * Default reporter used to process the messages with a callback function.
 */
private class CallbackReporter(callback: (ProcessedMessage) => Unit) extends Reporter {
  def process(processedMessage: ProcessedMessage) {
    callback(processedMessage)
  }
}