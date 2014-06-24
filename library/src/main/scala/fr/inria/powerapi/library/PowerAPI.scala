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

import java.io.File
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
  case class StartMonitoring(frequency: FiniteDuration, targets: List[Target])

  case object StopAll
  case object PowerAPIStopped
  case object StopAllMonitorings
}

/**
 * Used to monitor a process, processes or an app.
 * Here, the overhead of PowerAPI is not considered.
 * It's possible to get its energy consumption by following its PID with the corresponding case class.
 */
trait Target {
  var monitoredProcesses = mutable.Set[Process]()
  val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  // Get the pids for the monitoring.
  def getPids() 
  
  // Update the monitored processes with a fixed period.
  def update(clockSupervisor: ActorRef, clockid: Long, frequency: FiniteDuration) = {
    import ClockMessages.{ Ping, StartTick, StopTick }

    val timer = new Timer
    implicit val timeout = Timeout(frequency)

    timer.scheduleAtFixedRate(new TimerTask() {
      def run() {
        try {
          // Used to know when it's necessary to stop the timer
          // (i.e. when the clock is stopped or stop() was called on the API).
          val isAlive = Await.result(clockSupervisor ? Ping(clockid), frequency).asInstanceOf[Boolean]

          if(isAlive) {
            val currentProcesses = monitoredProcesses.clone()
            getPids()
            val oldProcesses = currentProcesses -- monitoredProcesses
            val newProcesses = monitoredProcesses -- currentProcesses
            oldProcesses.foreach(process => clockSupervisor ! StopTick(clockid, process))
            newProcesses.foreach(process => clockSupervisor ! StartTick(clockid, process))
            monitoredProcesses --= oldProcesses
            monitoredProcesses ++= newProcesses
          }
        }
        catch {
          case _: Exception => timer.cancel
        }
      }
    }, Duration.Zero.toMillis, frequency.toMillis)
  }
}

case class PIDS(pids: Int*) extends Target {
  getPids()

  def getPids() = {
    monitoredProcesses.clear()
    for(pid <- pids) {
      var isAlive = Seq("kill", "-0", pid.toString).!

      if(isAlive == 0) monitoredProcesses += Process(pid)
    }
  }
}

case class APPS(names: String*) extends Target {
  getPids()

  // Get the pids of the processes hidden by the given names.
  def getPids() = {
    monitoredProcesses.clear()
    names.foreach(name => {
      var lines = Seq("pgrep", name).lines_!.toArray
      lines = lines.filter(line => line.trim.toInt != currentPid)
      monitoredProcesses ++= (for(line <- lines) yield Process(line.trim.toInt))
    })
  }
}

case object ALL extends Target {
  getPids()

  // Get the pids of the processes hidden by the given names.
  def getPids() = {
    monitoredProcesses.clear()
    var lines = Seq("ps", "-A", "ho", "pid").lines_!.toArray
    lines = lines.filter(line => line.trim.toInt != currentPid)
    monitoredProcesses ++= (for(line <- lines) yield Process(line.trim.toInt))
  }
}

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
    case startMon: StartMonitoring => startMonitoring(sender, startMon.frequency, startMon.targets)
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
   * Starts the monitoring of processes which are represented by PIDS/APPS/ALL objects for a given clock frequency.
   * @param sender: actor reference of the sender, used to send the monitoring representative.
   * @param frequency: duration period monitoring.
   * @param targets: combination of PIDS/APPS/ALL.
   */
  def startMonitoring(sender: ActorRef, frequency: FiniteDuration, targets: List[Target]) {
    val allPids = scala.collection.mutable.Set[Process]()

    for(target <- targets) {
      allPids ++= target.monitoredProcesses
    }

    val clockid = startMonitoringArray(sender, allPids.toArray, frequency)

    for(target <- targets) {
      target.update(clockSupervisor, clockid, frequency)
    }
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

   /**
   * Starts the monitoring of an processes array for a given clock frequency.
   * @param sender: actor reference of the sender, used to send the monitoring representative.
   * @param processes: processes to monitor.
   * @param frequency: duration period monitoring.
   */
  private def startMonitoringArray(sender: ActorRef, processes: Array[Process], frequency: FiniteDuration): Long = {
    import ClockMessages.StartClock
    // Starts a monitoring actor, retrieves the representative of the monitoring and sends it to the API.
    val clockid = Await.result(clockSupervisor ? StartClock(processes, frequency), timeout.duration).asInstanceOf[Long]
    val monitoringRepr = Await.result(monitoringSupervisor ? StartMonitoringRepr(clockid), timeout.duration).asInstanceOf[Monitoring]
    sender ! monitoringRepr
    clockid
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
   * Starts the monitoring of PIDS/APPS/ALL for a given clock frequency.
   * @param frequency: duration period monitoring.
   * @param targets: combination of PIDS/APPS/ALL.
   */ 
  def start(frequency: FiniteDuration, targets: Target*): Monitoring = {
    Await.result(engine ? StartMonitoring(frequency, targets.toList), timeout.duration).asInstanceOf[Monitoring]
  }

  /**
   * Shutdown all the remaining actors.
   */
  def stop() = {
    Await.result(engine ? StopAll, timeout.duration)
    system.shutdown()
  }
}