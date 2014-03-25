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

import fr.inria.powerapi.core.{ Listener, Process, Reporter, ProcessedMessage }
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

class SimpleCpuReporter extends Reporter {
  def process(processedMessage: ProcessedMessage) {
    println(processedMessage)
  }
}

class Anonymous

class PowerAPISuite extends JUnitSuite {
  val currentPid = ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  /*@Test
  def testPowerAPI() {
    Array(classOf[CpuSensor], classOf[CpuFormula]).foreach(PowerAPI.startEnergyModule(_))

    PowerAPI.startMonitoring(
      process = Process(currentPid),
      duration = 500.milliseconds,
      processor = classOf[TimestampAggregator],
      listener = classOf[SimpleCpuReporter])
    Thread.sleep((5.seconds).toMillis)
    PowerAPI.stopMonitoring(
      process = Process(currentPid),
      duration = 500.milliseconds,
      processor = classOf[TimestampAggregator],
      listener = classOf[SimpleCpuReporter])

    Array(classOf[CpuSensor], classOf[CpuFormula]).foreach(PowerAPI.stopEnergyModule(_))
  }*/

  // @Test
  // def testAPIBakeryWithReporter() {
  //   val powerapi = new API("powerapi") with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
  //   powerapi.startMonitoring(
  //     processes = Array(Process(currentPid)), duration = 500.milliseconds, reporter = classOf[SimpleCpuReporter], timeout = 5.seconds)
  //   powerapi.stopMonitoring(processes = Array(Process(currentPid)), duration = 500.milliseconds)
  //   powerapi.stop
  // }

  /*@Test
  def testAPIBakeryWithFunctionAsReporter() {
    val powerapi = new API("powerapi") with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
    powerapi.startMonitoring(
      processes = Array(Process(currentPid)), duration = 1.seconds, reporter = { println(_)}, timeout = 5.seconds)
    powerapi.stopMonitoring(processes = Array(Process(currentPid)), duration = 1.seconds)
    powerapi.stop
  }

  @Test
  def testAPIBakeryWithActorRefAsReporter() {
    val powerapi = new API("powerapi") with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    val reporter = powerapi.system.actorOf(Props[SimpleCpuReporter], classOf[SimpleCpuReporter].getCanonicalName)

    powerapi.startMonitoring(
      processes = Array(Process(currentPid)), duration = 500.milliseconds, reporter = (reporter, classOf[SimpleCpuReporter]), timeout = 5.seconds)
    powerapi.stopMonitoring(processes = Array(Process(currentPid)), duration = 500.milliseconds)
    powerapi.stop
  }*/

  @Test
  def test() {
    //val powerapi = new API("powerapi") with SensorCpuProc with FormulaCpuMax with AggregatorTimestamp
    
  }
}
