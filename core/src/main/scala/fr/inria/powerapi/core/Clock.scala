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

  def acquire = {
    case _ => ()
  }
  def messagesToListen = Array()
}

/**
 * Define messages for the clock components
 */
object ClockSupervisor {
  case class StartClock(processes: Array[Process], frequency: FiniteDuration)
  case class RemoveClock(clockRef: ActorRef)
  case object StopWorker
  case object StopClocks

  case object ClocksStopped
}

object ClockWorker {
  case object TickIt
  case object UnTickIt
  case class WaitFor(duration: FiniteDuration)

  case object ClockStopped
}

/**
 * The PowerAPI architecture is based on a asynchronous architecture composed by several components.
 * Each component listen to an event bus and reacts following messages sent by the event bus.
 * Thus, each component is in a passive state and only run its business part following the sent message.
 *
 * At the bottom of this architecture, the ClockSupervisor component manages a pool of ClockWorkers to handle the several monitorings. 

 * It creates workers per monitoring to cut down the load.
 * Each worker schedules the Ticks' publishing on the event bus.
 */
class ClockSupervisor extends Actor with ActorLogging with ClockSupervisorConfiguration {
  import ClockSupervisor._
  import ClockWorker.{ TickIt, UnTickIt }
  implicit val timeout = Timeout(5.seconds)

  override def receive = LoggingReceive {
    case startClock: StartClock => startClockWorker(sender, startClock)
    case removeClock: RemoveClock => removeClockWorker(removeClock)
    case StopClocks => stopClockWorkers(sender)
    case unknown => throw new UnsupportedOperationException("unable to process message yes " + unknown)
  }

  val workers = new mutable.ArrayBuffer[ActorRef] with mutable.SynchronizedBuffer[ActorRef]

  /**
   * Starts a clock worker.
   */
  def startClockWorker(sender: ActorRef, startClock: StartClock) {
    val duration = if (startClock.frequency < minimumTickDuration) {
      if(log.isWarningEnabled) log.warning("unable to schedule a duration less than that specified in the configuration file (" + startClock.frequency + " vs " + minimumTickDuration)
      minimumTickDuration
    } else {
      startClock.frequency
    }

    if(log.isDebugEnabled) log.debug("New clock will be created.")
    
    val clockRef = context.actorOf(Props(classOf[ClockWorker], context.system.eventStream, startClock.processes, startClock.frequency))
    workers += clockRef
    clockRef ! TickIt
    sender ! clockRef
  }

  /**
   * Allows to refresh the workers buffer when a clock is shuts down itself.
   */
  def removeClockWorker(removeClock: RemoveClock) {
    workers -= removeClock.clockRef
  }

  /**
   * Stop all the remaining clocks.
   */
  def stopClockWorkers(sender: ActorRef) {
    workers.foreach(clock => {
      Await.result(clock ? StopWorker, timeout.duration)
      context.stop(clock)
    })

    workers.clear
    sender ! ClocksStopped
  }
}

/**
 * ClockWorker is used to cut down the load.
 * We use one ClockWorker per monitoring to cut down the load.
 */
class ClockWorker(eventBus: EventStream, processes: Array[Process], frequency: FiniteDuration) extends Actor with ActorLogging {
  import ClockSupervisor.{ RemoveClock, StopWorker }
  import ClockWorker.{ TickIt, ClockStopped, UnTickIt, WaitFor }

  def receive = LoggingReceive {
    case TickIt => makeItTick
    case StopWorker => stop(sender)
    case waitFor: WaitFor => runningForDuration(sender, waitFor)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  val subscriptions = new mutable.ArrayBuffer[TickSubscription] with mutable.SynchronizedBuffer[TickSubscription]
  var scheduler: Cancellable = null

  /**
   * Publishes Tick for each Subscription.
   */
  def makeItTick() {
    def subscribe() {
      processes.foreach(process => {
        subscriptions += TickSubscription(process, frequency)
      })
    }

    def schedule() {
      if (scheduler == null) {
        scheduler = context.system.scheduler.schedule(Duration.Zero, frequency)({
          val timestamp = System.currentTimeMillis
          subscriptions.foreach(subscription => eventBus.publish(Tick(self, subscription, timestamp)))
        })(context.system.dispatcher)
      }
    }

    subscribe
    schedule
  }

  /**
   * Used to stop a clock from the outside (no timer defined)
   */
  def stop(sender: ActorRef) {
    unmakeItTick
    sender ! ClockStopped
  }

  /**
   * Used to specify a clock's lifespan with a timer.
   * When the timer is over, the clock shuts down itself.
   */
  def runningForDuration(sender: ActorRef, waitFor: WaitFor) {
    if(waitFor.duration != Duration.Zero) {
      context.system.scheduler.scheduleOnce(waitFor.duration) {
        unmakeItTick
        context.parent ! RemoveClock(self)
        sender ! ClockStopped
      }(context.system.dispatcher)
    }
  }

  /**
   * Remove all the subscriptions.
   */
  private def unmakeItTick() {
    def unsubscribe() {
      if (!subscriptions.isEmpty) {
        processes.foreach(process => {
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
  }
}