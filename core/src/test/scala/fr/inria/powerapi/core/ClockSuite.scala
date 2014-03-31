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

import scala.collection.mutable.HashMap
import scala.collection.mutable.SynchronizedMap
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.Test
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite}
import org.scalatest.Matchers

import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.actor.ActorRef
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout

case object Result

class ByClockTickReceiver extends akka.actor.Actor with ActorLogging {
  val receivedTicks = new HashMap[ActorRef, Int] with SynchronizedMap[ActorRef, Int]

  private def increment(clockRef: ActorRef) {
    val currentTick = receivedTicks getOrElse(clockRef, 0)
    receivedTicks += (clockRef -> (currentTick + 1))
  }

  def receive = {
    case tick: Tick => increment(tick.clockRef)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }
}

class ClockSuite extends JUnitSuite with Matchers with AssertionsForJUnit {
  import ClockSupervisor._
  import ClockWorker._

  implicit val system = ActorSystem("ClockTest")
  val clock = TestActorRef[ClockSupervisor]
  implicit val timeout = Timeout(1.minutes)

  @Test
  def testClockBehavior() {
    val tickReceiver = TestActorRef[ByClockTickReceiver]
    system.eventStream.subscribe(tickReceiver, classOf[Tick])

    val clockMonitoring1 = Await.result(clock ? StartClock(Array(Process(123)), 500.milliseconds), 5.seconds).asInstanceOf[ActorRef]
    val clockMonitoring2 = Await.result(clock ? StartClock(Array(Process(124)), 1000.milliseconds), 5.seconds).asInstanceOf[ActorRef]
    val clockMonitoring3 = Await.result(clock ? StartClock(Array(Process(125)), 1500.milliseconds), 5.seconds).asInstanceOf[ActorRef]

    val monitoring1ending = Await.result(clockMonitoring1 ? WaitFor(3200.milliseconds), (3200.milliseconds + 1.seconds))
    monitoring1ending should equal(ClockStopped)

    val monitoring2ending = Await.result(clockMonitoring3 ? WaitFor(2200.milliseconds), (2200.milliseconds + 1.seconds))
    monitoring2ending should equal(ClockStopped)

    val monitoring3ending  = Await.result(clockMonitoring2 ? WaitFor(1200.milliseconds), (1200.milliseconds + 1.seconds))
    monitoring3ending should equal(ClockStopped)

    Await.result(clock ? StopClocks, 5.seconds) should equal(ClocksStopped)

    val receivedTicks = tickReceiver.underlyingActor.receivedTicks
    
    receivedTicks getOrElse(clockMonitoring1, 0) should {
      equal(7) or equal(7 + 1)
    }
    receivedTicks getOrElse(clockMonitoring2, 0) should {
      equal(6) or equal(6 + 1)
    }
    receivedTicks getOrElse(clockMonitoring3, 0) should {
      equal(4) or equal(4 + 1)
    }
  }
}