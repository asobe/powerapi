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
 * Define messages for the clock components
 */
object ClockSupervisor {
  case class StartTickSub(subscription: TickSubscription) extends Message
  case class StopTickSub(subscription: TickSubscription) extends Message
  case class Running(duration: FiniteDuration) extends Message
  case object Ack
}

object ClockWorker {
  case class TickIt(subscription: TickSubscription) extends Message
  case class UnTickIt(subscription: TickSubscription) extends Message 

  case object Empty
  case object NonEmpty

  case object Stop

  // Factory to instanciate an actor with parameters
  def props(eventBus: EventStream, duration: FiniteDuration): Props = Props(new ClockWorker(eventBus, duration))
}

/**
 * The PowerAPI architecture is based on a asynchronous architecture composed by several components.
 * Each component listen to an event bus and reacts following messages sent by the event bus.
 * Thus, each component is in a passive state and only run its business part following the sent message.
 *
 * At the bottom of this architecture, the ClockSupervisor component manages a pool of ClockWorkers to schedule the Tick subscriptions. 

 * The supervisor reacts on StartTickSub and StopTickSub published on the event bus (published when a monitoring is asked).
 * It creates workers in relation of the clock frequency to cut down the load. 
 * Each worker schedules the Ticks publishing on the event bus.
 */
class ClockSupervisor extends Component with ClockSupervisorConfiguration {
  import ClockSupervisor._
  import ClockWorker.{ TickIt, UnTickIt, Empty, NonEmpty, Stop }

  def messagesToListen = Array(classOf[StartTickSub], classOf[StopTickSub])

  def acquire = {
    case subscribe: StartTickSub => doSubscription(subscribe)
    case unsubscribe: StopTickSub => undoSubscription(unsubscribe)
    case running: Running => runningForDuration(running, sender)
    case unknown => throw new UnsupportedOperationException("unable to process message yes " + unknown)
  }

  val workers = new mutable.HashMap[FiniteDuration, ActorRef] with mutable.SynchronizedMap[FiniteDuration, ActorRef]

  /**
   * Start the monitoring of a given process by starting a worker if the frequency is not already handled, else forward to the right one.
   */
  def doSubscription(subscribe: StartTickSub) {
    val duration = if (subscribe.subscription.duration < minimumTickDuration) {
      if (log.isWarningEnabled) log.warning("unable to schedule a duration less than that specified in the configuration file (" + subscribe.subscription.duration + " vs " + minimumTickDuration)
      minimumTickDuration
    } else {
      subscribe.subscription.duration
    }

    if(workers.contains(duration)) {
      if(log.isDebugEnabled) log.debug("worker already forked for this clock frequency, we will use it.")
      val actorRef = workers(duration)
      actorRef ! TickIt(subscribe.subscription)
    }

    else {
      if(log.isDebugEnabled) log.debug("worker is not created for this clock frequency, we will create it.")
      val actorRef = context.actorOf(ClockWorker.props(context.system.eventStream, duration))
      workers += (duration -> actorRef)
      actorRef ! TickIt(subscribe.subscription)
    }
  }

  /**
   * Stop the monitoring of a given process, it can also stop a worker if there is not any more processes for its frequency.
   */
  def undoSubscription(unsubscribe: StopTickSub) {
    implicit val timeout = Timeout(5.seconds)

    val duration = if (unsubscribe.subscription.duration < minimumTickDuration) {
      if (log.isWarningEnabled) log.warning("unable to schedule a duration less than that specified in the configuration file (" + unsubscribe.subscription.duration + " vs " + minimumTickDuration)
      minimumTickDuration
    } else {
      unsubscribe.subscription.duration
    }

    if(workers.contains(duration)) {
      if(log.isDebugEnabled) log.debug("worker is forked for this clock frequency, forward the message to the right worker.")
      val actorRef = workers(duration)
      val response = Await.result(actorRef ? UnTickIt(unsubscribe.subscription), timeout.duration).asInstanceOf[Object]
      response match {
        case Empty => context.stop(actorRef); workers -= duration; if(log.isDebugEnabled) log.debug("worker stopped.")
        case NonEmpty => if(log.isDebugEnabled) log.debug("worker has remaining jobs.")
      }
    }

    else {
      if(log.isWarningEnabled) log.debug("worker does not exist for this frequency, unable to stop the subscription.")
    }
  }

  /**
   * Allows to run the ClockSupervisor during a fixed period.
   * Stop all the workers when the time is finished and send an ack to the API.
   */
  def runningForDuration(running: Running, sender: ActorRef) = {
    def stopWorkers() = {
      workers.foreach({
        case (duration, actorRef) => {
          actorRef ! Stop
          workers -= duration
          context.stop(actorRef)
          if(log.isDebugEnabled) log.debug("clock worker stopped.")
        }
      })
    }

    if(running.duration != Duration.Zero) {
      context.system.scheduler.scheduleOnce(running.duration) {
        stopWorkers
        sender ! Ack
      }(context.system.dispatcher)
    }

    // Don't the stop the workers if the duration was not fixed (old way to use PowerAPI with Thread sleeps).
    else sender ! Ack
  }
}

/**
 * ClockWorker is used to cut down the load.
 * It starts a scheduler related to the clock frequency for the Ticks publishing.
 */
class ClockWorker(eventBus: EventStream, duration: FiniteDuration) extends Actor with ActorLogging {
  import ClockWorker.{ TickIt, UnTickIt, Empty, NonEmpty, Stop }

  def receive = LoggingReceive {
    case subscribe: TickIt => makeItTick(subscribe)
    case unsubscribe: UnTickIt => unmakeItTick(unsubscribe)
    case Stop => stopWorker()
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }

  val subscriptions = new mutable.ArrayBuffer[TickSubscription] with mutable.SynchronizedBuffer[TickSubscription]
  var scheduler: Cancellable = null

  /**
   * Publishes Tick for each Subscription
   */
  def makeItTick(implicit tickIt: TickIt) {
    def subscribe(implicit tickIt: TickIt) {
      subscriptions += tickIt.subscription
    }

    def schedule(implicit tickIt: TickIt) {
      if (scheduler == null) {
        scheduler = context.system.scheduler.schedule(Duration.Zero, duration)({
          val timestamp = System.currentTimeMillis
          subscriptions.foreach(subscription => eventBus.publish(Tick(subscription, timestamp)))
        })(context.system.dispatcher)
      }
    }

    subscribe
    schedule
  }

  /**
   * Stop the subscription
   */
  def unmakeItTick(implicit untickIt: UnTickIt) {
    def unsubscribe(implicit untickIt: UnTickIt) {
      if (!subscriptions.isEmpty) {
        subscriptions -= untickIt.subscription
      }
    }

    def unschedule(implicit untickIt: UnTickIt) {
      // If there is not any more subscription for this frequency, we stop the scheduler
      if (subscriptions.isEmpty) {
        if(scheduler != null) {
          scheduler.cancel
          sender ! Empty
        }
      }

      else sender ! NonEmpty
    }

    unsubscribe
    unschedule
  }

  /**
   * Allows to shutdown completely a worker.
   */
  def stopWorker() = {
    subscriptions.clear()
    if(scheduler != null) scheduler.cancel
  }
}