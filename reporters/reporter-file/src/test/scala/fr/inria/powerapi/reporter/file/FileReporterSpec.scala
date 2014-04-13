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
package fr.inria.powerapi.reporter.file

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
  val testPath = "powerapi-reporter-file-test"
}

trait ConfigurationMock extends Configuration {
  override lazy val filePath = ConfigurationMock.testPath
}

case class ProcessedMessageMock(tick: Tick, energy: Energy, device: String = "mock") extends ProcessedMessage

class FileReporterMock extends FileReporter with ConfigurationMock

case class LineMock(processedMessage: ProcessedMessage) {
  override def toString() =
    "timestamp=" + processedMessage.tick.timestamp + ";" +
    "process=" + processedMessage.tick.subscription.process + ";" +
    "device=" + processedMessage.device + ";" +
    "power=" + processedMessage.energy.power
}

@RunWith(classOf[JUnitRunner])
class FileReporterSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("file-reporter-spec")
  val fileReporter = TestActorRef[FileReporterMock]

  val prM1 = ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), 1), Energy.fromPower(1))
  val prM2 = ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), 2), Energy.fromPower(2))
  val prM3 = ProcessedMessageMock(Tick(1, TickSubscription(Process(123), 1 second), 3), Energy.fromPower(3))

  "A FileReporter" should "process a ProcessedMessage" in {
    fileReporter.underlyingActor.process(prM1)
    fileReporter.underlyingActor.process(prM2)
    fileReporter.underlyingActor.process(prM3)

    val testFile = Path.fromString(ConfigurationMock.testPath)
    testFile.isFile should be (true)
    testFile.size.get should be > 0L
    testFile.lines() should (
      have size 3 and
      contain(LineMock(prM1).toString) and 
      contain(LineMock(prM2).toString) and
      contain(LineMock(prM3).toString)
    )
    testFile.delete(true)
  }
}