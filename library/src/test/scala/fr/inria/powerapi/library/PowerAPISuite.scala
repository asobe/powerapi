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
import fr.inria.powerapi.sensor.cpu.proc.SensorCpuProc
import fr.inria.powerapi.formula.cpu.max.FormulaCpuMax
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp
import fr.inria.powerapi.processor.aggregator.process.AggregatorProcess
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
  def testOneAPIWithPIDS {
    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)

    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi.start(PIDS(1, 2, 3), 1500.milliseconds).attachReporter(classOf[FileReporterMock1]).waitFor(7500.milliseconds)
    powerapi.stop

    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.delete(true)
    Thread.sleep(1000)
  }

  @Test
  def testOneAPIWithAPPS {
    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi.start(APPS("firefox"), 1.seconds).attachReporter({println(_)}).waitFor(3.seconds)
    powerapi.stop
  }

  @Test
  def testOneAPIWithALL {
    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    powerapi.start(ALL(), 1.seconds).attachReporter({println(_)}).waitFor(5.seconds)
    powerapi.stop
  }

  @Test
  def testOneAPIWithPIDSAPPS {
    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    powerapi.start(PIDS(currentPid), APPS("firefox"), 1.seconds).attachReporter({println(_)}).waitFor(3.seconds)
    powerapi.stop
  }

  @Test
  def testOneAPIAttachProcess {
    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)

    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    val monitoring = powerapi.start(PIDS(1, 2), 500.milliseconds).attachReporter(classOf[FileReporterMock1])
    monitoring.attachProcess(Process(3)).waitFor(3.seconds)
    powerapi.stop

    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.delete(true)
    Thread.sleep(1000)
  }

  @Test
  def testOneAPIDetachProcess {
    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)

    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    val monitoring = powerapi.start(PIDS(1, 3), 1.seconds).attachReporter(classOf[FileReporterMock1])
    monitoring.detachProcess(Process(3)).attachProcess(Process(2))
    val processesList = monitoring.getMonitoredProcesses()
    processesList should contain allOf(Process(1), Process(2))
    
    Thread.sleep(4000)
    powerapi.stop

    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.delete(true)
    Thread.sleep(1000)
  }

  @Test
  def testOneAPIWithReporter {
    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    val testFileM2 = Path.fromString(ConfigurationMock2.testPath)

    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi.start(processes = Array(Process(1), Process(2)), frequency = 1.seconds).attachReporter(classOf[FileReporterMock1])
    powerapi.start(processes = Array(Process(currentPid)), frequency = 500.milliseconds).attachReporter(classOf[FileReporterMock2]).waitFor(5.seconds)
    powerapi.stop

    testFileM1.isFile should be (true)
    testFileM2.isFile should be (true)
    testFileM1.size.get should be > 0L
    testFileM2.size.get should be > 0L

    val testProcess1 = "Process(1)"
    val testProcess2 = "Process(2)"
    val testCurrentPid = "Process(" + currentPid + ")"
    testFileM1.lines().foreach(line => 
      line should not include(testCurrentPid)
    )
    testFileM2.lines().foreach(line => 
      line should (
        not include(testProcess1) and
        not include(testProcess2)
      )
    )

    testFileM1.delete(true)
    testFileM2.delete(true)
    Thread.sleep(1000)
  }

  @Test
  def testOneAPIWithRefAsReporter {
    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)

    implicit val system = ActorSystem("api-test")
    val reporter = system.actorOf(Props[FileReporterMock1])
    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    powerapi.start(processes = Array(Process(currentPid)), frequency = 500.milliseconds).attachReporter(reporter).waitFor(5.seconds)
    powerapi.stop

    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.delete(true)
    Thread.sleep(1000)
  }

  @Test
  def testOneAPIWithFunctionAsReporter {
    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
    powerapi.start(processes = Array(Process(currentPid)), frequency = 500.milliseconds).attachReporter(processedMessage => {
      lazy val output = Resource.fromFile(ConfigurationMock1.testPath)
      output.append(LineMock(processedMessage).toString)
    }).waitFor(5.seconds)
    
    powerapi.stop

    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    testFileM1.isFile should be (true)
    testFileM1.size.get should be > 0L

    testFileM1.delete(true)
    Thread.sleep(1000)
  }

  @Test
  def testTwoAPI {
    val powerapi = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi.start(processes = Array(Process(1)), frequency = 1.seconds).attachReporter(classOf[FileReporterMock1]).waitFor(3.seconds)
    powerapi.stop

    val powerapi2 = new PAPI with SensorCpuProc with FormulaCpuMax with AggregatorProcess
    powerapi2.start(processes = Array(Process(2)), frequency = 1.seconds).attachReporter(classOf[FileReporterMock2]).waitFor(5.seconds)
    powerapi2.stop

    val testFileM1 = Path.fromString(ConfigurationMock1.testPath)
    val testFileM2 = Path.fromString(ConfigurationMock2.testPath)
    testFileM1.isFile should be (true)
    testFileM2.isFile should be (true)
    testFileM1.size.get should be > 0L
    testFileM2.size.get should be > 0L

    val testProcess1 = "Process(1)"
    val testProcess2 = "Process(2)"
    testFileM1.lines().foreach(line => 
      line should not include(testProcess2)
    )
    testFileM2.lines().foreach(line => 
      line should not include(testProcess1)
    )

    testFileM1.delete(true)
    testFileM2.delete(true)
    Thread.sleep(1000)
  }
}