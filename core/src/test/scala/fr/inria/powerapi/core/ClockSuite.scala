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
  val receivedTicks = new HashMap[Long, Int] with SynchronizedMap[Long, Int]

  private def increment(clockid: Long) {
    val currentTick = receivedTicks getOrElse(clockid, 0)
    receivedTicks += (clockid -> (currentTick + 1))
  }

  def receive = {
    case tick: Tick => increment(tick.clockid)
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }
}

class ClockSuite extends JUnitSuite with Matchers with AssertionsForJUnit {
  import ClockMessages._

  implicit val system = ActorSystem("ClockTest")
  val clock = TestActorRef[ClockSupervisor]
  implicit val timeout = Timeout(10.seconds)

  @Test
  def testClockBehavior() {
    val tickReceiver = TestActorRef[ByClockTickReceiver]
    system.eventStream.subscribe(tickReceiver, classOf[Tick])

    val clock1 = Await.result(clock ? StartClock(Array(Process(123)), 500.milliseconds), timeout.duration).asInstanceOf[Long]
    val clock2 = Await.result(clock ? StartClock(Array(Process(124)), 1000.milliseconds), timeout.duration).asInstanceOf[Long]
    val clock3 = Await.result(clock ? StartClock(Array(Process(125)), 1500.milliseconds), timeout.duration).asInstanceOf[Long]

    val clock2ending = Await.result(clock ? WaitFor(clock3, 5400.milliseconds), (5400.milliseconds + 1.seconds))
    clock2ending should equal(ClockStoppedAck)

    val clock3ending  = Await.result(clock ? WaitFor(clock2, 1200.milliseconds), (1200.milliseconds + 1.seconds))
    clock3ending should equal(ClockStoppedAck)

    Await.result(clock ? StopAllClocks, timeout.duration) should equal(AllClocksStoppedAck)

    Thread.sleep((5.seconds).toMillis)

    val receivedTicks = tickReceiver.underlyingActor.receivedTicks
    
    receivedTicks getOrElse(clock1, 0) should {
      equal(13) or equal(13 + 1)
    }
    receivedTicks getOrElse(clock2, 0) should {
      equal(6) or equal(6 + 1)
    }
    receivedTicks getOrElse(clock3, 0) should {
      equal(4) or equal(4 + 1)
    }
  }
}