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

import scala.concurrent.duration.DurationInt

import org.scalatest.junit.ShouldMatchersForJUnit
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import fr.inria.powerapi.formula.cpu.max.CpuFormula
import fr.inria.powerapi.formula.mem.single.MemFormula
import fr.inria.powerapi.sensor.cpu.sigar.CpuSensor
import fr.inria.powerapi.sensor.mem.sigar.MemSensor
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.library.PowerAPI
import java.lang.management.ManagementFactory
import scalax.file.Path
import org.scalatest.junit.JUnitSuite
import org.junit.After
import org.junit.Before
import org.junit.Test
import fr.inria.powerapi.processor.aggregator.process.ProcessAggregator

object ConfigurationMock {
  val testPath = "powerapi-reporter-gnuplot-test"
  val testPids = List(2538,2344)
}

trait ConfigurationMock extends Configuration {
  override lazy val filePath = ConfigurationMock.testPath
  override lazy val pids = ConfigurationMock.testPids
}

class GnuplotReporterMock extends GnuplotReporter with ConfigurationMock

class GnuplotReporterTest extends JUnitSuite with ShouldMatchersForJUnit {

  @Before
  def setUp() {
    Array(
      classOf[CpuSensor],
      classOf[CpuFormula]
    ).foreach(PowerAPI.startEnergyModule(_))
  }

  @Test
  def testRun() {
    ConfigurationMock.testPids.foreach(pid => 
      PowerAPI.startMonitoring(
        process = Process(pid),
        duration = 1.second
      )
    )
    PowerAPI.startMonitoring(
      processor = classOf[ProcessAggregator],
      listener = classOf[GnuplotReporterMock]
    )
    Thread.sleep((6 seconds).toMillis)
    PowerAPI.stopMonitoring(
      processor = classOf[ProcessAggregator],
      listener = classOf[GnuplotReporterMock]
    )
    ConfigurationMock.testPids.foreach(pid => 
      PowerAPI.stopMonitoring(
        process = Process(pid),
        duration = 1.second
      )
    )
    
    val testFile = Path.fromString(ConfigurationMock.testPath)
    testFile.isFile should be (true)
    testFile.size.get should be > 0L
    testFile.lines().size should be >= 4 // greater than 4 lines of monitoring result during 6 seconds of 1 second monitoring.
    testFile.lines().foreach(line => line.split(' ').size should equal(3)) // equal to 2 processes to monitoring plus the timestamp
    testFile.delete(true)
  }

  @After
  def tearDown() {
    Array(
      classOf[CpuSensor],
      classOf[CpuFormula]
    ).foreach(PowerAPI.stopEnergyModule(_))
  }

}
