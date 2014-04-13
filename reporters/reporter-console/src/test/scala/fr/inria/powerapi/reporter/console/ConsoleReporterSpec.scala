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
package fr.inria.powerapi.reporter.console

import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.core.Process

import fr.inria.powerapi.core.ProcessedMessage

import scala.collection
import scala.concurrent.duration.DurationInt

import akka.testkit.TestActorRef
import org.junit.runner.RunWith
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite, JUnitRunner}
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import akka.actor.ActorSystem


case class ProcessedMessageMock(tick: Tick, energy: Energy, device: String = "mock") extends ProcessedMessage

class ConsoleReporterMock extends ConsoleReporter {
  val received = collection.mutable.Set[String]()

  override def process(processedMessage: ProcessedMessage) = {
    received += Line(processedMessage).toString
  }
}

case class LineMock(processedMessage: ProcessedMessage) {
  override def toString() =
    "timestamp=" + processedMessage.tick.timestamp + ";" +
    "process=" + processedMessage.tick.subscription.process + ";" +
    "device=" + processedMessage.device + ";" +
    "power=" + processedMessage.energy.power
}

@RunWith(classOf[JUnitRunner])
class ConsoleReporterSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("console-reporter-spec")
  val consoleReporter = TestActorRef[ConsoleReporterMock]

  val prM1 = ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), 1), Energy.fromPower(1))
  val prM2 = ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), 2), Energy.fromPower(2))
  val prM3 = ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), 3), Energy.fromPower(3))

  "A ConsoleReporter" should "process a ProcessedMessage" in {
    consoleReporter.underlyingActor.process(prM1)
    consoleReporter.underlyingActor.process(prM2)
    consoleReporter.underlyingActor.process(prM3)

    consoleReporter.underlyingActor.received should have size 3
    
    consoleReporter.underlyingActor.received should (
      contain(LineMock(prM1).toString) and
      contain(LineMock(prM2).toString) and
      contain(LineMock(prM3).toString)
    )
  }
}