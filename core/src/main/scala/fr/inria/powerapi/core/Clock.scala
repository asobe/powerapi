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

import java.util.concurrent.TimeUnit

import collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.{ EventStream, LoggingReceive }
import akka.pattern.ask
import akka.util.Timeout

/**
 * Clock configuration.
 */
trait ClockSupervisorConfiguration extends Configuration {
  lazy val minimumTickDuration = load { conf =>
    Duration.create(conf.getString("akka.scheduler.tick-duration")) match {
      case Duration(length, unit) => FiniteDuration(length, unit)
    }
  }(10.milliseconds)
}

/**
 * Define messages for the clock components.
 */
object ClockMessages {
  case class StartClock(processes: Array[Process], frequency: FiniteDuration)
  case class StopClock(clockid: Long)
  case class WaitFor(clockid: Long, duration: FiniteDuration)
  case class StartTick(clockid: Long, process: Process)
  case class StopTick(clockid: Long, process: Process)
  case class Ping(clockid: Long)

  case object TickIt
  case object UnTickIt
  case object ClockStoppedAck
  case object AllClocksStoppedAck
  case object StopWorker
  case object StopAllClocks
}

/**
 * The PowerAPI architecture is based on a asynchronous architecture composed by several components.
 * Each component listen to an event bus and reacts following messages sent by the event bus.
 * Thus, each component is in a passive state and only run its business part following the sent message.
 *
 * At the bottom of this architecture, the ClockSupervisor component manages a pool of ClockWorkers to handle the several monitorings. 
 * One clock is created per monitoring with an unique ID to identify it. Each clock schedules the Ticks publishing for its given frequency
 * on the event bus.
 */
class ClockSupervisor extends Actor with ActorLogging with ClockSupervisorConfiguration {
  import ClockMessages._
  implicit val timeout = Timeout(5.seconds)

  override def receive = LoggingReceive {
    case startClock: StartClock => startClockWorker(sender, startClock)
    case stopClock: StopClock => stopClockWorker(sender, stopClock)
    case startTick: StartTick => startTickProcess(startTick)
    case stopTick: StopTick => stopTickProcess(stopTick)
    case waitFor: WaitFor => runningForDuration(sender, waitFor)
    case ping: Ping => clockIsStillAlive(sender, ping)
    case StopAllClocks => stopAllClocks(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  val workers = new mutable.HashMap[Long, ActorRef] with mutable.SynchronizedMap[Long, ActorRef]

  /**
   * Start a clock worker.
   */
  def startClockWorker(sender: ActorRef, startClock: StartClock) {
    val duration = if (startClock.frequency < minimumTickDuration) {
      if(log.isWarningEnabled) log.warning("unable to schedule a duration less than that specified in the configuration file (" + startClock.frequency + " vs " + minimumTickDuration)
      minimumTickDuration
    } else {
      startClock.frequency
    }
    
    val clockid = System.nanoTime
    val clockRef = context.actorOf(Props(classOf[ClockWorker], clockid, context.system.eventStream, startClock.processes, startClock.frequency))
    workers += (clockid -> clockRef)
    // Send the clockid
    sender ! clockid
    clockRef ! TickIt
    if(log.isDebugEnabled) log.debug("A clock is started, referenced by " + clockid + ".")
  }

  /**
   * Stop a clock worker, references inside the internal buffer
   */
  def stopClockWorker(sender: ActorRef, stopClock: StopClock) {
    // Check if the clockid exists, else there is a problem with clock workers
    if(workers.contains(stopClock.clockid)) {
      val clockRef = workers(stopClock.clockid)
      val ack = Await.result(clockRef ? UnTickIt, timeout.duration)

      if(ack == ClockStoppedAck) { 
        workers -= stopClock.clockid
        context.stop(clockRef)
        sender ! ClockStoppedAck
        if(log.isDebugEnabled) log.debug("Clock referenced by " + stopClock.clockid + " is now stopped and killed.")
      }
      else if(log.isDebugEnabled) log.debug("Clock referenced by " + stopClock.clockid + " sends an wrong ack.")
    }
    else if(log.isDebugEnabled) log.debug("Clock does not exist, we can't stop it.")
  }

  /**
   * Allows to start the tick publishing for a given process and clock.
   */
  def startTickProcess(startTick: StartTick) {
    // clock exists ?
    if(workers.contains(startTick.clockid)) {
      val clockRef = workers(startTick.clockid)
      clockRef ! startTick
    }
    else if(log.isDebugEnabled) log.debug("Clock does not exist, we can't attach it a new process.")
  }

  /**
   * Allows to stop the tick publishing for a given process and clock.
   */
  def stopTickProcess(stopTick: StopTick) {
    // clock exists ?
    if(workers.contains(stopTick.clockid)) {
      val clockRef = workers(stopTick.clockid)
      clockRef ! stopTick
    }
    else if(log.isDebugEnabled) log.debug("Clock does not exist, we can't detach the given process.")
  }

  /**
   * Stop all the clock workers
   */
  def stopAllClocks(sender: ActorRef) {
    workers.foreach { 
      case (clockid, clockRef) => {
        val ack = Await.result(clockRef ? UnTickIt, timeout.duration)

        if(ack == ClockStoppedAck) { 
          context.stop(clockRef)
          if(log.isDebugEnabled) log.debug("Clock referenced by " + clockid + " is now stopped and killed.")
        }
      }
    }

    // Delete all the workers
    workers.clear
    // Send an ack when all the clocks are stopped
    sender ! AllClocksStoppedAck
  }

  /**
   * Used to specify a clock's lifespan with a timer.
   * When the timer is over, the clock shuts down itself.
   */
  def runningForDuration(sender: ActorRef, waitFor: WaitFor) {
    if(waitFor.duration != Duration.Zero) {
      context.system.scheduler.scheduleOnce(waitFor.duration) {
        stopClockWorker(sender, StopClock(waitFor.clockid))
      }(context.system.dispatcher)
    }
  }

  /**
   * Used to know if a clock worker is still alive or not.
   */
  def clockIsStillAlive(sender: ActorRef, isAlive: Ping) {
    if(workers.contains(isAlive.clockid)) {
      sender ! true
    }
    else sender ! false
  }
}

/**
 * ClockWorker is used to cut down the load. One clock is created per monitoring and works at its frequency.
 */
class ClockWorker(clockid: Long, eventBus: EventStream, processes: Array[Process], frequency: FiniteDuration) extends Actor with ActorLogging {
  import ClockMessages._

  def receive = LoggingReceive {
    case TickIt => makeItTick
    case UnTickIt => unmakeItTick(sender)
    case startTick: StartTick => attachProcess(startTick)
    case stopTick: StopTick => detachProcess(stopTick)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  val subscriptions = new mutable.HashSet[TickSubscription] with mutable.SynchronizedSet[TickSubscription]
  var handledProcesses = new mutable.HashSet[Process] with mutable.SynchronizedSet[Process]
  handledProcesses ++= processes

  var scheduler: Cancellable = null

  /**
   * Publishes Tick for each subscription.
   */
  def makeItTick() {
    def subscribe() {
      handledProcesses.foreach(process => {
        subscriptions += TickSubscription(process, frequency)
      })
    }

    def schedule() {
      if (scheduler == null) {
        scheduler = context.system.scheduler.schedule(Duration.Zero, frequency)({
          val timestamp = System.currentTimeMillis
          subscriptions.foreach(subscription => eventBus.publish(Tick(clockid, subscription, timestamp)))
        })(context.system.dispatcher)
      }
    }

    subscribe
    schedule
  }

  /*
   * Remove all the susbcriptions, in the aim to stop the clock
   */
  def unmakeItTick(sender: ActorRef) {
    def unsubscribe() {
      if (!subscriptions.isEmpty) {
        handledProcesses.foreach(process => {
          subscriptions -= TickSubscription(process, frequency)
        })
      }
    }

    def unschedule() {
      if (subscriptions.isEmpty) {
        if(scheduler != null) {
          scheduler.cancel
        }
      }
    }

    unsubscribe
    unschedule

    // Sends an ack message
    sender ! ClockStoppedAck
  }

  /**
   * Allows to do the subscription for a new process.
   */
  def attachProcess(startTick: StartTick) = {
    val subscription = TickSubscription(startTick.process, frequency)
    if(!subscriptions.contains(subscription)) {
      handledProcesses += startTick.process
      subscriptions += subscription
    }
  }

  /**
   * Allows to stop a subscription for a given process.
   */
  def detachProcess(stopTick: StopTick) = {
    val subscription = TickSubscription(stopTick.process, frequency)
    if(subscriptions.contains(subscription)) {
      handledProcesses -= stopTick.process
      subscriptions -= subscription
    }
  }
}