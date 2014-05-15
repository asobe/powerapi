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

import java.io.File
import java.nio.ByteBuffer

import scalax.file.Path

import akka.actor.{ Actor, ActorLogging, Props }
import akka.event.LoggingReceive

import net.fusejna.DirectoryFiller
import net.fusejna.ErrorCodes
import net.fusejna.FuseException
import net.fusejna.StructFuseFileInfo.FileInfoWrapper
import net.fusejna.StructStat.StatWrapper
import net.fusejna.types.TypeMode.{ ModeWrapper, NodeType }
import net.fusejna.util.FuseFilesystemAdapterFull

import fr.inria.powerapi.core.{ Process, ProcessedMessage, Reporter }
import fr.inria.powerapi.library.{ PIDS, Monitoring }


/**
 * Provide a virtual file system interface to monitor processes.
 *
 * @author lhuertas
 */

private object Pids {
  // List of monitoring PIDs.
  // The value is compute from processed messages.
  // [pid -> (timestamp, energy, power)]
  lazy val _list = collection.mutable.HashMap[String, (Long, Double, Double)]()
  def list(implicit mon: Monitoring) = {
    val currentPids = mon.getMonitoredProcesses.map(_.pid.toString).toSet
    val oldPids = _list.keySet -- currentPids
    _list --= oldPids
    val newPids = currentPids -- _list.keySet
    newPids.foreach(pid => _list += (pid -> _list.getOrElse(pid, (0, 0.0, 0.0))))
    _list
  }
}

class FuseReporter(mon: Monitoring) extends Reporter {
  override def preStart() {
    context.actorOf(Props(classOf[PowerAPIFuse], mon), name = "FUSE")
  }
  
  def process(processedMessage: ProcessedMessage) {
    val pid = processedMessage.tick.subscription.process.pid.toString
    if (Pids._list contains pid) {
      Pids._list(pid) = (processedMessage.tick.timestamp,
                         processedMessage.energy.power,
                         Pids._list(pid)._3 + processedMessage.energy.power)
    }
  }
}

class PowerAPIFuse(implicit mon: Monitoring) extends FuseFilesystemAdapterFull with Actor with ActorLogging {
  // ------------------
  // --- Actor part --------------------------------------------------
  
  override def preStart() {
    this.log(false).mount(mountPoint)
  }
  override def postStop() {
    this.unmount()
  }
  def receive = {case _ => ()}


  // ---------------------
  // --- FUSE-jna part --------------------------------------------------

  lazy val mountPoint = {
    Path.fromString("/pfs").createDirectory(createParents=false, failIfExists=false)
    "/pfs"
  }

  val pidsFileName = "pids"
  lazy val conf = collection.mutable.HashMap[String, String](
    ("frequency" -> "1")
  )
  lazy val Dir = """/|/energy""".r
  lazy val EnergyPidFileFormat = """/energy/(\d+)/(\w+)""".r
  lazy val EnergyPidDirFormat = """/energy/(\d+)""".r
  
  
  override def getattr(path: String, stat: StatWrapper) =
    path match {
      case Dir() => {
        stat.setMode(NodeType.DIRECTORY)
        0
      }
      case EnergyPidDirFormat(pid) if Pids.list contains pid => {
        stat.setMode(NodeType.DIRECTORY)
        0
      }
      case EnergyPidFileFormat(pid, file) if ((Pids.list contains pid) && (conf contains file)) => {
        stat.setMode(NodeType.FILE).size(conf(file).length+1)
        0
      }
      case EnergyPidFileFormat(pid, file) if ((Pids.list contains pid) && (file == "energy")) => {
        while (Pids.list.apply(pid)._1 <= 0) Thread.sleep(1000)
        val v = Pids.list.apply(pid)
        stat.setMode(NodeType.FILE).size((pid + " " + v._3 + "\n").length+1)
        0
      }
      case EnergyPidFileFormat(pid, file) if ((Pids.list contains pid) && (file == "power")) => {
        while (Pids.list.apply(pid)._1 <= 0) Thread.sleep(1000)
        val v = Pids.list.apply(pid)
        stat.setMode(NodeType.FILE).size((pid + " " + v._1 + " " + v._2).length+1)
        0
      }
      case _ => -ErrorCodes.ENOENT()
    }

  override def read(path: String, buffer: ByteBuffer, size: Long, offset: Long, info: FileInfoWrapper) =
  {
    val content = path match {
      case EnergyPidFileFormat(pid, file) if ((Pids.list contains pid) && (conf contains file)) => conf(file) + "\n"
      case EnergyPidFileFormat(pid, file) if ((Pids.list contains pid) && (file == "energy")) => {
        while (Pids.list.apply(pid)._1 <= 0) Thread.sleep(1000)
        val v = Pids.list.apply(pid)
        pid + " " + v._3 + "\n"
      }
      case EnergyPidFileFormat(pid, file) if ((Pids.list contains pid) && (file == "power")) => {
        while (Pids.list.apply(pid)._1 <= 0) Thread.sleep(1000)
        val v = Pids.list.apply(pid)
        pid + " " + v._1 + " " + v._2 + "\n"
      }
      case _ => ""
    }
    
    val s = content.substring(offset.asInstanceOf[Int],
                Math.max(offset,
                         Math.min(content.length() - offset, offset + size)
                ).asInstanceOf[Int])
    buffer.put(s.getBytes())
    s.getBytes().length
  }
  
  override def readdir(path: String, filler: DirectoryFiller) =
    path match {
      case File.separator => {
        filler.add("energy")
        0
      }
      case "/energy" => {
        Pids.list.keySet.foreach(pid => filler.add(pid))
        0
      }
      case EnergyPidDirFormat(pid) if Pids.list contains pid => {
        conf.keySet.foreach(confFile => filler.add(confFile))
        filler.add("power")
        filler.add("energy")
        0
      }
      case _ => -ErrorCodes.ENOENT()
    }
    
  override def mkdir(path: String, mode: ModeWrapper) =
    path match {
      case EnergyPidDirFormat(pid) if !(Pids.list contains pid) => {
        mon.attachProcess(Process(pid.toInt))
        0
      }
      case _ => -ErrorCodes.ENOENT()
    }
    
  override def rmdir(path: String) =
    path match {
      case EnergyPidDirFormat(pid) if Pids.list contains pid => {
        mon.detachProcess(Process(pid.toInt))
        0
      }
      case _ => -ErrorCodes.ENOENT()
    }
}
