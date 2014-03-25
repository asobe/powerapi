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

import org.junit.Ignore
import org.junit.Test
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite}
import org.scalatest.Matchers

import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout

case object Result

class ByProcessTickReceiver extends akka.actor.Actor with ActorLogging {
  val receivedTicks = new HashMap[TickSubscription, Int] with SynchronizedMap[TickSubscription, Int]

  private def increment(tickSubscription: TickSubscription) {
    val currentTick = receivedTicks getOrElse(tickSubscription, 0)
    receivedTicks += (tickSubscription -> (currentTick + 1))
  }

  def receive = {
    case tick: Tick => increment(tick.subscription)
    case Result => sender ! receivedTicks
    case unknown => throw new UnsupportedOperationException("unable to process message " + unknown)
  }
}

class SimpleTickReceiver extends akka.actor.Actor with ActorLogging {
  var receivedTicks = 0

  def receive = {
    case tick: Tick => receivedTicks += 1
  }
}

class ClockSuite extends JUnitSuite with Matchers with AssertionsForJUnit {

  import ClockSupervisor._

  implicit val system = ActorSystem("ClockTest")
  val clock = TestActorRef[ClockSupervisor]

  @Test
  def testMessagesToListen() {
    implicit val timeout = Timeout(5.seconds)
    val request = clock ? MessagesToListen
    val messages = Await.result(request, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]

    messages should have size 2
    messages(0) should be(classOf[StartTickSub])
    messages(1) should be(classOf[StopTickSub])
  }

  @Test
  def testReceivedSimpleTicks() {
    val tickReceiver = TestActorRef[ByProcessTickReceiver]
    system.eventStream.subscribe(tickReceiver, classOf[Tick])

    clock ! StartTickSub(TickSubscription(Process(123), 500.milliseconds))
    clock ! StartTickSub(TickSubscription(Process(124), 1000.milliseconds))
    clock ! StartTickSub(TickSubscription(Process(125), 1500.milliseconds))
    Thread.sleep(3200)

    clock ! StopTickSub(TickSubscription(Process(123), 500.milliseconds))
    Thread.sleep(2200)

    clock ! StopTickSub(TickSubscription(Process(124), 500.milliseconds))
    clock ! StopTickSub(TickSubscription(Process(125), 1500.milliseconds))

    Thread.sleep(1200)

    clock ! StopTickSub(TickSubscription(Process(124), 1000.milliseconds))

    // There are not any more tick on the bus, waiting to do the assertions
    Thread.sleep(1000)

    val receivedTicks = tickReceiver.underlyingActor.receivedTicks
    receivedTicks getOrElse(TickSubscription(Process(123), 500.milliseconds), 0) should {
      equal(7) or equal(7 + 1)
    }
    receivedTicks getOrElse(TickSubscription(Process(124), 1000.milliseconds), 0) should {
      equal(6) or equal(6 + 1)
    }
    receivedTicks getOrElse(TickSubscription(Process(125), 1500.milliseconds), 0) should {
      equal(4) or equal(4 + 1)
    }
  }
}