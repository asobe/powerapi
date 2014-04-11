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

import fr.inria.powerapi.core.{ APIComponent, ClockMessages, ClockSupervisor, Message, MessagesToListen, Process }

import java.util.{ Timer, TimerTask }

import collection.mutable

import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }
import scala.sys.process._

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout

/**
 * PowerAPI object encapsulates all the messages.
 */
object PowerAPIMessages {
  case class StartComponent[U <: APIComponent](companion: U)
  case class StartSubscription(actorRef: ActorRef)
  case class StartMonitoringArray(processes: Array[Process], frequency: FiniteDuration)
  case class StartMonitoringPIDS(pids: PIDS, frequency: FiniteDuration)

  case object StopAll
  case object PowerAPIStopped
  case object StopAllMonitorings
}

/**
 * Used to monitore a process, processes or an app.
 */
case class PIDS(pids: Int*) {
  val monitoredProcesses = for(pid <- pids.toArray) yield Process(pid)
}

// TODO: tests !
case class APPS(names: String*) {
  var monitoredProcesses = new mutable.HashSet[Process]
  getPids()

  // Get the pids of the processes hidden by the given names.
  def getPids() = {
    names.foreach(name => {
      val cmd = Seq("ps", "-C", name, "ho", "pid")
      if(cmd .! == 0) {
        val results = cmd.lines.toArray
        monitoredProcesses ++= (for(result <- results) yield Process(result.trim.toInt))
      }
    })
  }

  // Update the pids every 250ms, and interacts with the clock to start/stop the subscriptions.
  def update(clockid: Long)(implicit clockSupervisor: ActorRef) {
    import ClockMessages.{ StartTick, StopTick }
    val timer = new Timer

    timer.scheduleAtFixedRate(new TimerTask() {
      def run() {
        val currentProcesses = monitoredProcesses
        getPids()
        val oldProcesses = currentProcesses -- monitoredProcesses
        val newProcesses = monitoredProcesses -- currentProcesses
        oldProcesses.foreach(process => clockSupervisor ! StopTick(clockid, process))
        newProcesses.foreach(process => clockSupervisor ! StartTick(clockid, process))
      }
    }, Duration.Zero.toMillis, (250.milliseconds).toMillis)
  }
}

// TODO: code + tests
case object ALL

/**
 * Represents the main actor of the API. Used to handle all the actors created for one API.
 */
class PowerAPI extends Actor with ActorLogging {
  import PowerAPIMessages._
  import ClockMessages.StopAllClocks
  import MonitoringMessages.StartMonitoringRepr

  implicit val timeout = Timeout(5.seconds)
  // Stores the actor references for all the components (excepts the ClockSupervisor).
  val components = new mutable.ArrayBuffer[ActorRef] with mutable.SynchronizedBuffer[ActorRef]
  var clockSupervisor: ActorRef = null
  var monitoringSupervisor: ActorRef = null

  // Starts the mandatory supervisors.
  override def preStart(): Unit = {
    clockSupervisor = context.actorOf(Props[ClockSupervisor])
    monitoringSupervisor = context.actorOf(Props(classOf[MonitoringSupervisor], clockSupervisor))
  }

  def receive = LoggingReceive {
    case StartComponent(companion) => startComponent(companion)
    case startSubscription: StartSubscription => process(startSubscription)
    case startMonitoring: StartMonitoringArray => startMonitoringArray(sender, startMonitoring.processes, startMonitoring.frequency)
    case startMonitoring: StartMonitoringPIDS => startMonitoringPIDS(sender, startMonitoring.pids, startMonitoring.frequency)
    case StopAll => stopAll(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
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

  /**
   * Starts the monitoring of an processes array for a given clock frequency.
   * @param processes: processes to monitor.
   * @param frequency: duration period monitoring.
   */
  def startMonitoringArray(sender: ActorRef, processes: Array[Process], frequency: FiniteDuration) {
    import ClockMessages.StartClock
    // Starts a monitoring actor, retrieves the representative of the monitoring and sends it to the API.
    val clockid = Await.result(clockSupervisor ? StartClock(processes, frequency), timeout.duration).asInstanceOf[Long]
    val monitoringRepr = Await.result(monitoringSupervisor ? StartMonitoringRepr(clockid), timeout.duration).asInstanceOf[Monitoring]
    sender ! monitoringRepr
  }

  /**
   * Starts the monitoring of pids (which are represented by a case class) for a given clock frequency.
   * @param pids: case class used to represent the processes to monitor.
   * @param frequency: duration period monitoring.
   */
  def startMonitoringPIDS(sender: ActorRef, pids: PIDS, frequency: FiniteDuration) {
    startMonitoringArray(sender, pids.monitoredProcesses.toArray, frequency)
  }

  /**
   * Allows to start a component with its companion obect.
   */
  def startComponent[U <: APIComponent](companion: U) = {
    val actorRef = companion.apply(self)
    // It is maybe None if the component is a singleton and already started
    actorRef match {
      // Do the actor's subscription on the event bus.
      case Some(actorRef) => process(StartSubscription(actorRef))
      case None => if(log.isDebugEnabled) log.debug(companion.getClass + " is already started and is a singleton component.")
    }
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
}

/**
 * Main API used as a dependency for each trait component. It's the main entry to
 * interact with the main API actor.
 */
class PAPI extends fr.inria.powerapi.core.API {
  import PowerAPIMessages._

  implicit lazy val system = ActorSystem(System.currentTimeMillis + "")
  lazy val engine = system.actorOf(Props[PowerAPI], "powerapi")
  implicit val timeout = Timeout(5.seconds)
  
  /**
   * Starts the component associated to the given type.
   * @param componentType: component type to start.
   */
  def configure[U <: APIComponent](companion: U) {
    engine ! StartComponent(companion)
  }

  /**
   * Starts the monitoring of an array of processes for a given clock frequency.
   * @param processes: processes to monitor.
   * @param frequency: duration period monitoring.
   */
  def start(processes: Array[Process], frequency: FiniteDuration): Monitoring = {
    Await.result(engine ? StartMonitoringArray(processes, frequency), timeout.duration).asInstanceOf[Monitoring]
  }

  /**
   * Starts the monitoring of pids (represents by a case class) for a given clock frequency.
   * @param pids: case class which contains the pids to monitor.
   * @param frequency: duration period monitoring.
   */
  def start(pids: PIDS, frequency: FiniteDuration): Monitoring = {
    Await.result(engine ? StartMonitoringPIDS(pids, frequency), timeout.duration).asInstanceOf[Monitoring]
  }

  // TODO
  // def start(apps: APPS): Monitoring = {
  //   null
  // }

  // def start(processes: PIDS, apps: APPS): Monitoring = {
  //   null
  // }

  /**
   * Shutdown all the remaining actors.
   */
  def stop() = {
    Await.result(engine ? StopAll, timeout.duration)
    system.shutdown()
  }
}