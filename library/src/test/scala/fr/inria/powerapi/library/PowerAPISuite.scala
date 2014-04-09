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
package fr.inria.powerapi.library

import fr.inria.powerapi.core.{ Process, Reporter, ProcessedMessage }
import fr.inria.powerapi.reporter.file.FileReporter

import scala.concurrent.duration.DurationInt
import org.scalatest.junit.JUnitSuite
import org.scalatest.Matchers
import java.lang.management.ManagementFactory
import org.junit.Test
import scala.concurrent.duration.Duration

import akka.actor.{ ActorSystem, Props }
import akka.testkit.TestActorRef
import scalax.io.Resource
import scalax.file.Path

object ConfigurationMock1 {
  lazy val testPath = "powerapi-reporter-file-test-monitoring1"
}

object ConfigurationMock2 {
  lazy val testPath = "powerapi-reporter-file-test-monitoring2"
}

trait ConfigurationMock1 extends fr.inria.powerapi.reporter.file.Configuration {
  override lazy val filePath = ConfigurationMock1.testPath
}

trait ConfigurationMock2 extends fr.inria.powerapi.reporter.file.Configuration {
  override lazy val filePath = ConfigurationMock2.testPath
}

class FileReporterMock1 extends FileReporter with ConfigurationMock1
class FileReporterMock2 extends FileReporter with ConfigurationMock2

case class LineMock(processedMessage: ProcessedMessage) {
  override def toString() =
    "timestamp=" + processedMessage.tick.timestamp + ";" +
    "process=" + processedMessage.tick.subscription.process + ";" +
    "device=" + processedMessage.device + ";" +
    "power=" + processedMessage.energy.power + scalax.io.Line.Terminators.NewLine.sep
}

class PowerAPISuite extends JUnitSuite with Matchers {
  val currentPid = ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  @Test
  def testOneAPIWithReporter {
    val powerapi = new API with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi.start(processes = Array(Process(1), Process(2)), frequency = 1.seconds).attachReporter(classOf[FileReporterMock1])
    powerapi.start(processes = Array(Process(currentPid)), frequency = 500.milliseconds).attachReporter(classOf[FileReporterMock2]).waitFor(5.seconds)
    powerapi.stop

    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    val testFileM2 = Path.fromString(ConfigurationMock2.testPath)
    testFileM1.isFile should be (true)
    testFileM2.isFile should be (true)
    testFileM1.size.get should be > 0L
    testFileM2.size.get should be > 0L

    testFileM1.lines().size should (be >= 9 and be <= 11)
    testFileM2.lines().size should (be >= 9 and be <= 11)

    val testProcess1 = "Process(1)"
    val testProcess2 = "Process(2)"
    val testCurrentPid = "Process(" + currentPid + ")"
    testFileM1.lines().foreach(line => 
      line should (
        not include(testCurrentPid) and
        (include(testProcess1) or include(testProcess2))
      )
    )
    testFileM2.lines().foreach(line => 
      line should (
        not include(testProcess1) and
        not include(testProcess2) and
        include(testCurrentPid)
      )
    )

    testFileM1.delete(true)
    testFileM2.delete(true)
  }

  @Test
  def testOneAPIWithRefAsReporter {
    implicit val system = ActorSystem("api-test")
    val reporter = system.actorOf(Props[FileReporterMock1])
    val powerapi = new API with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    powerapi.start(processes = Array(Process(currentPid)), frequency = 500.milliseconds).attachReporter(reporter).waitFor(5.seconds)
    powerapi.stop

    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.lines().size should (be >= 9 and be <= 11)
    testFileM1.delete(true)
  }

  @Test
  def testOneAPIWithFunctionAsReporter {
    val powerapi = new API with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
    powerapi.start(processes = Array(Process(currentPid)), frequency = 500.milliseconds).attachReporter(processedMessage => {
      lazy val output = Resource.fromFile(ConfigurationMock1.testPath)
      output.append(LineMock(processedMessage).toString)
    }).waitFor(5.seconds)
    
    powerapi.stop

    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.lines().size should (be >= 9 and be <= 11)
    testFileM1.delete(true)
  }

  @Test
  def testTwoAPI {
    val powerapi = new API with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi.start(processes = Array(Process(1)), frequency = 1.seconds).attachReporter(classOf[FileReporterMock1]).waitFor(3.seconds)
    powerapi.stop

    val powerapi2 = new API with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi2.start(processes = Array(Process(2)), frequency = 1.seconds).attachReporter(classOf[FileReporterMock2]).waitFor(5.seconds)
    powerapi2.stop

    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    val testFileM2 = Path.fromString(ConfigurationMock2.testPath)
    testFileM1.isFile should be (true)
    testFileM2.isFile should be (true)
    testFileM1.size.get should be > 0L
    testFileM2.size.get should be > 0L

    testFileM1.lines().size should (be >= 2 and be <= 4)
    testFileM2.lines().size should (be >= 4 and be <= 6)

    val testProcess1 = "Process(1)"
    val testProcess2 = "Process(2)"
    testFileM1.lines().foreach(line => 
      line should (
        not include(testProcess2) and include(testProcess1)
      )
    )
    testFileM2.lines().foreach(line => 
      line should (
        not include(testProcess1) and include(testProcess2)
      )
    )

    testFileM1.delete(true)
    testFileM2.delete(true)
  }
}