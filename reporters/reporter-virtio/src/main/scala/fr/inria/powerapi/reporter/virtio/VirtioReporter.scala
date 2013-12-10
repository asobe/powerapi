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
package fr.inria.powerapi.reporter.virtio

import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.core.Reporter

import java.io.OutputStream
import java.io.BufferedReader
import java.io.FileReader
import java.io.File

import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress
import org.newsclub.net.unix.AFUNIXSocketException

import scala.collection.JavaConversions
import com.typesafe.config.Config

/**
 * Listen to AggregatedMessage and send its content to the virtual machine using VirtioSerial.
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  /**
   * Need the path to the unix domain socket for VirtioSerial. 
   */
  lazy val socketpath = load { _.getString("powerapi.cpu.virtio.host") }("/tmp/")

  lazy val vmsConfiguration = Map[Int,Int]()
} 

class VirtioReporter extends Reporter with Configuration {
  lazy val sockets = scala.collection.mutable.Map.empty[Int, AFUNIXSocket]
  for((vmPid, port) <- vmsConfiguration) {
    println("vmPID and port")
   
    println(vmPid)
    println(port)
    var sock = AFUNIXSocket.newInstance()
    var socketaddress = new AFUNIXSocketAddress(new File(socketpath + "port" + port))
    sock.connect(socketaddress)
    sockets(vmPid) = sock
  }

  case class Line(processedMessage: ProcessedMessage) {
    override def toString() =
      "timestamp=" + processedMessage.tick.timestamp + ";" +
        "process=" + processedMessage.tick.subscription.process + ";" +
        "device=" + processedMessage.device + ";" +
        "power=" + processedMessage.energy.power + " from virtio reporter "
  }

  def process(processedMessage: ProcessedMessage) {
    val os = sockets(processedMessage.tick.subscription.process.pid).getOutputStream()
    val data = processedMessage.energy.power.toString+"\n"
    //println(data)
    os.write(data.getBytes())
    println(Line(processedMessage))
  }
}

