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
 * Listen to AggregatedMessage and display its content into the console.
 *
 * @author abourdon
 */
class VirtioReporter () extends Reporter {

  val socketpath = "/tmp/port2"
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
    val line = Line(processedMessage).toString()
    val data = line.slice(line.indexOfSlice("power=")+6,line.indexOfSlice(" from v"))+"\n"
    //println(data)
    //val data = "241\n" -- for testing
    os.write(data.getBytes()) 
    println(Line(processedMessage))
  }

}
