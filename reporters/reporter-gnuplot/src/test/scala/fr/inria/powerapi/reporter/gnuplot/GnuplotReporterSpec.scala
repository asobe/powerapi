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
package fr.inria.powerapi.reporter.gnuplot

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
import scalax.file.Path

object ConfigurationMock {
  val testPath = "powerapi-reporter-gnuplot-test"
  val testPids = List(123, 456)
}

trait ConfigurationMock extends Configuration {
  override lazy val filePath = ConfigurationMock.testPath
  override lazy val pids = ConfigurationMock.testPids
}

case class ProcessedMessageMock(tick: Tick, energy: Energy, device: String = "mock") extends ProcessedMessage

class GnuplotReporterMock extends GnuplotReporter with ConfigurationMock

// TODO: Rewrite the GnuplotReporter, too many dependencies with the too module (just write the values in a file ..)
@RunWith(classOf[JUnitRunner])
class GnuplotReporterSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("gnuplot-reporter-spec")
  val gnuplotReporter = TestActorRef[GnuplotReporterMock]

  val prM1 = ProcessedMessageMock(Tick(1, TickSubscription(1, Process(123), 1 second), 1), Energy.fromPower(1))
  val prM2 = ProcessedMessageMock(Tick(1, TickSubscription(1, Process(456), 1 second), 1), Energy.fromPower(6))
  val prM3 = ProcessedMessageMock(Tick(1, TickSubscription(1, Process(123), 1 second), 2), Energy.fromPower(2))
  val prM4 = ProcessedMessageMock(Tick(1, TickSubscription(1, Process(456), 1 second), 2), Energy.fromPower(8))
  val prM5 = ProcessedMessageMock(Tick(1, TickSubscription(1, Process(123), 1 second), 3), Energy.fromPower(4))
  val prM6 = ProcessedMessageMock(Tick(1, TickSubscription(1, Process(456), 1 second), 3), Energy.fromPower(10))

  "A GnuplotReporter" should "process a ProcessedMessage" in {
    gnuplotReporter.underlyingActor.process(prM1)
    gnuplotReporter.underlyingActor.process(prM2)
    gnuplotReporter.underlyingActor.process(prM3)
    gnuplotReporter.underlyingActor.process(prM4)
    gnuplotReporter.underlyingActor.process(prM5)
    gnuplotReporter.underlyingActor.process(prM6)

    val testFile = Path.fromString(ConfigurationMock.testPath)
    testFile.isFile should be (true)
    testFile.size.get should be > 0L
    testFile.lines() should have size 2
    testFile.delete(true)
  }
}