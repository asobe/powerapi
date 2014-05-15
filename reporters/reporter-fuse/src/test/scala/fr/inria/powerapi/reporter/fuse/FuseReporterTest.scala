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
package fr.inria.powerapi.reporter.fuse

import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}
import scalax.io.Resource
import scalax.file.Path

import akka.actor.Props
import akka.testkit.TestActorRef

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.junit.ShouldMatchersForJUnit

import fr.inria.powerapi.formula.cpu.reg.FormulaCpuReg
import fr.inria.powerapi.library.{ PAPI, PIDS, Monitoring }
import fr.inria.powerapi.processor.aggregator.process.AggregatorProcess
import fr.inria.powerapi.sensor.cpu.proc.reg.SensorCpuProcReg

class PowerAPIFuseMock(implicit mon: Monitoring) extends PowerAPIFuse {
  override lazy val mountPoint = {
    Path.fromString("./pfs").createDirectory(createParents=false, failIfExists=false)
    "./pfs"
  }
}
class FuseReporterMock(mon: Monitoring) extends FuseReporter(mon) {
  override def preStart() {
    context.actorOf(Props(classOf[PowerAPIFuseMock], mon), name = "FUSEMock")
  }
}

class FuseReporterTest extends JUnitSuite with ShouldMatchersForJUnit {
  
  val powerapi = new PAPI with SensorCpuProcReg with FormulaCpuReg with AggregatorProcess
  
  @Before
  def setUp() {
    val monitoring = powerapi.start(PIDS(), 1.seconds)
    
    // create and start fuse interface actor
    val fuseService = powerapi.system.actorOf(
      Props(classOf[FuseReporterMock], monitoring),
      name = "FuseReporter")
    
    monitoring.attachReporter(fuseService)
  }

  @Test
  def testRun() {
    Thread.sleep((1.second).toMillis)
  
    Resource.fromInputStream(Runtime.getRuntime.exec(Array("ls","-l","pfs/")).getInputStream).lines()
      .toList(1).split("\\s").last should equal ("energy")
    Resource.fromInputStream(Runtime.getRuntime.exec(Array("ls","-l","pfs/energy/")).getInputStream).lines()
      .toList should have size 1
    
    Runtime.getRuntime.exec(Array("mkdir","pfs/energy/1234"))
    
    Resource.fromInputStream(Runtime.getRuntime.exec(Array("ls","-l","pfs/energy")).getInputStream).lines()
      .toList.map(_.split("\\s").last) should contain ("1234")
    val pidDir = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ls","-l","pfs/energy/1234")).getInputStream).lines()
      .toList.map(_.split("\\s").last)
    pidDir should have size 4
    pidDir should contain ("frequency")
    pidDir should contain ("energy")
    pidDir should contain ("power")
    
    Resource.fromInputStream(Runtime.getRuntime.exec(Array("cat","pfs/energy/1234/frequency")).getInputStream).lines()
      .toList(0).size should be > 0
      Resource.fromInputStream(Runtime.getRuntime.exec(Array("cat","pfs/energy/1234/energy")).getInputStream).lines()
      .toList(0).size should be > 0
      Resource.fromInputStream(Runtime.getRuntime.exec(Array("cat","pfs/energy/1234/power")).getInputStream).lines()
      .toList(0).size should be > 0
      
    Runtime.getRuntime.exec(Array("rmdir","pfs/energy/1234"))
    
    Resource.fromInputStream(Runtime.getRuntime.exec(Array("ls","-l","pfs/energy/")).getInputStream).lines()
      .toList should have size 1
  }
  
  @After
  def tearDown() {
    powerapi.stop
    
    //Path("pfs").deleteRecursively()
  }
}
