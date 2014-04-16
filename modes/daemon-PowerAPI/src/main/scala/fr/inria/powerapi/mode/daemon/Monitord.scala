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
package fr.inria.powerapi.mode.daemon

import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}
import scalax.file.Path

import akka.actor.Props

import org.apache.commons.daemon.{ Daemon, DaemonContext, DaemonInitException }

import fr.inria.powerapi.formula.cpu.reg.FormulaCpuReg
import fr.inria.powerapi.library.{ PAPI, PIDS }
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp
import fr.inria.powerapi.processor.aggregator.process.AggregatorProcess
import fr.inria.powerapi.reporter.fuse.FuseReporter
import fr.inria.powerapi.reporter.rest.RestReporter
import fr.inria.powerapi.sensor.cpu.proc.reg.SensorCpuProcReg

class Monitord extends Daemon {

  // =====================
  // --- PowerAPI part ---
  
  val powerapi = new PAPI with SensorCpuProcReg with FormulaCpuReg with AggregatorProcess
  
  def beforeStart() {
    Path("pfs").createDirectory(createParents=false,failIfExists=false)
    
    val monitoring = powerapi.start(PIDS(), 1.seconds)
    
    // create and start fuse interface actor
    val fuseService = powerapi.system.actorOf(
      Props(classOf[FuseReporter], monitoring),
      name = "FuseReporter")
    
    // create and start rest service actor
    val restService = powerapi.system.actorOf(
      Props(classOf[RestReporter], powerapi,  monitoring),
      name = "RestReporter")
    
    monitoring.attachReporter(fuseService).attachReporter(restService)
  }
  def beforeEnd() {
    powerapi.stop
    
    //Path("pfs").deleteRecursively()
  }


  // ===================
  // --- Daemon part ---

  var stopped = false
  val monitor = new Thread(){
    override def start() {
      this.synchronized {
        Monitord.this.stopped = false
        super.start
      }
    }
    override def run() {
      while (!stopped) Thread.sleep(2000)
    }
  }
  
  override def init(daemonContext: DaemonContext) {
    val args = daemonContext.getArguments
    beforeStart
  }
  
  override def start() {
    monitor.start
  }
  
  override def stop() {
    stopped = true
    try {
      monitor.join(1000)
    } catch {
      case e: InterruptedException => {
        System.err.println(e.getMessage)
        throw e
      }
    }
  }
  
  override def destroy() {
    beforeEnd
  }
}
