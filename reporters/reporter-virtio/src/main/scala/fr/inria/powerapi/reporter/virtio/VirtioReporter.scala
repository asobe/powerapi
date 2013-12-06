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

/**
 * Listen to AggregatedMessage and send its content to the virtual machine using VirtioSerial.
 */
trait Configuration extends fr.inria.powerapi.core.Configuration {
  /**
   * Need the path to the unix domain socket for VirtioSerial. 
   */
  lazy val socketpath = load { _.getString("powerapi.cpu.virtio.host") }("") // not sure whether there should be standard value, might create conflicts
}

class VirtioReporter () extends Reporter with Configuration {

  //val socketpath = "/tmp/port2" // TODO: add this to config file
  val sock = AFUNIXSocket.newInstance()
  val socketaddress = new AFUNIXSocketAddress(new File(socketpath))
  sock.connect(socketaddress)

  case class Line(processedMessage: ProcessedMessage) {
    override def toString() =
      "timestamp=" + processedMessage.tick.timestamp + ";" +
        "process=" + processedMessage.tick.subscription.process + ";" +
        "device=" + processedMessage.device + ";" +
        "power=" + processedMessage.energy.power + " from virtio reporter "
  }

  def process(processedMessage: ProcessedMessage) {
    val os = sock.getOutputStream()
    val data = processedMessage.energy.power.toString+"\n"
    //println(data)
    os.write(data.getBytes()) 
    println(Line(processedMessage))
  }

}
