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
package fr.inria.powerapi.reporter.jfreechart

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

@RunWith(classOf[JUnitRunner])
class JFreeChartReporterSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("jfreechart-reporter-spec")
  val jfreechartReporter = TestActorRef[JFreeChartReporter]

  "A JFreeChartReporter" should "process a ProcessedMessage" in {
    val begin = System.currentTimeMillis
    var current = begin

    while(current <= (begin + (5.seconds).toMillis)) {
      jfreechartReporter.underlyingActor.process(ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), current), Energy.fromPower(5)))
      current += (1.seconds).toMillis
      Thread.sleep((1.seconds).toMillis)
    }
  }
}