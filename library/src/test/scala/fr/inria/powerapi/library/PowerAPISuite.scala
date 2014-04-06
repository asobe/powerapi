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
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import fr.inria.powerapi.sensor.cpu.proc.CpuSensor
import fr.inria.powerapi.formula.cpu.max.CpuFormula
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator

import scala.concurrent.duration.DurationInt
import akka.actor.{ ActorLogging, Props }
import org.scalatest.junit.JUnitSuite
import org.scalatest.Matchers
import java.lang.management.ManagementFactory
import org.junit.Test
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SimpleCpuReporter extends Reporter {
  def process(processedMessage: ProcessedMessage) {
    println(processedMessage)
  }
}

class PowerAPISuite extends JUnitSuite {
  implicit val timeout = Duration.Inf
  val currentPid = ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  def reporterConsoleTest1(processedMessage: ProcessedMessage) = {
    val test = "TEST1 => clockid=" + processedMessage.tick.clockid + ";" +
    "timestamp=" + processedMessage.tick.timestamp + ";" +
    "process=" + processedMessage.tick.subscription.process + ";" +
    "power=" + processedMessage.energy.power

    println(test)
  }

  def reporterConsoleTest2(processedMessage: ProcessedMessage) = {
    val test = "TEST2 => clockid=" + processedMessage.tick.clockid + ";" +
    "timestamp=" + processedMessage.tick.timestamp + ";" +
    "process=" + processedMessage.tick.subscription.process + ";" +
    "power=" + processedMessage.energy.power

    println(test)

  }

  @Test
  def test() {
    var powerapi = new API with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    powerapi.start(processes = Array(Process(currentPid)), frequency = 1.seconds).attachReporter({reporterConsoleTest1(_)})
    powerapi.start(processes = Array(Process(8063)), frequency = 500.milliseconds).attachReporter({reporterConsoleTest2(_)}).waitFor(10.seconds)

    Thread.sleep((10.seconds).toMillis)
    powerapi.stop()
  }

  // @Test
  // def testPowerAPI() {
  //   Array(classOf[CpuSensor], classOf[CpuFormula]).foreach(PowerAPI.startEnergyModule(_))

  //   PowerAPI.startMonitoring(
  //     process = Process(currentPid),
  //     duration = 500.milliseconds,
  //     processor = classOf[TimestampAggregator],
  //     listener = classOf[SimpleCpuReporter])
  //   Thread.sleep((5.seconds).toMillis)
  //   PowerAPI.stopMonitoring(
  //     process = Process(currentPid),
  //     duration = 500.milliseconds,
  //     processor = classOf[TimestampAggregator],
  //     listener = classOf[SimpleCpuReporter])

  //   Array(classOf[CpuSensor], classOf[CpuFormula]).foreach(PowerAPI.stopEnergyModule(_))
  // }

  // @Test
  // def testAPIBakeryWithReporterComponent() {
  //   var powerapi = new API("powerapi", duration) with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp

  //   powerapi.attachReporter(classOf[SimpleCpuReporter])
    
  //   powerapi.startMonitoring(processes = Array(Process(currentPid)), frequency = 2.seconds)
    
  //   Await.result(powerapi.ack, awaitDuration)
  //   powerapi.stop
  // }

  // @Test
  // def testAPIBakeryWithActorRefAsReporter() {
  //   var powerapi = new API("powerapi", duration) with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
  //   val reporter = powerapi.system.actorOf(Props[SimpleCpuReporter])

  //   powerapi.attachReporter(reporter)
    
  //   powerapi.startMonitoring(processes = Array(Process(currentPid)), frequency = 500.milliseconds)
    
  //   Await.result(powerapi.ack, awaitDuration)
  //   powerapi.stop
  // }

  // @Test
  // def testAPIBakeryWithFunctionAsReporter() {
  //   var powerapi = new API("powerapi") with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
  //   powerapi.startMonitoring(processes = Array(Process(currentPid)), frequency = 1.seconds).attachReporter({println(_)})
  //   powerapi.startMonitoring(processes = Array(Process(currentPid)), frequency = 1.seconds).waitFor(duration)
  //   powerapi.stop
  // }

    // @Test
  // def testAPIBakeryWithFunctionAsReporter() {
  //   var powerapi = new API("powerapi") with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
  //   powerapi.startMonitoring(processes = Array(Process(currentPid)), frequency = 1.seconds).attachReporter({println(_)})
  //   powerapi.startMonitoring(processes = Array(Process(currentPid)), frequency = 1.seconds).waitFor(duration)
  //   powerapi.stop
  // }
}