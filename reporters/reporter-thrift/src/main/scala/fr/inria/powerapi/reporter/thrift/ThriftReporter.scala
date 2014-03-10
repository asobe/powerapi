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
package fr.inria.powerapi.reporter.thrift

import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.core.Reporter

//zeromq 
import org.zeromq.ZMQ

// thrift imports
import org.apache.thrift._
import org.apache.thrift.protocol._
import org.apache.thrift.protocol.TProtocolFactory
import org.apache.thrift.server._
import org.apache.thrift.transport._

import java.util.{Map => JMap}
import java.util.Collections._

import scala.collection.JavaConversions._

/**
 * Listen to AggregatedMessage and creates Apache Thrift object.
 *
 * @author mkurpicz
 */
class ThriftReporter extends Reporter {

  val zmqcontext = ZMQ.context(1)
  val publisher = zmqcontext.socket(ZMQ.PUB) // publisher socket
  publisher.connect("tcp://localhost:5556")

  var interval_index = 0
  //def this() = this(org.zeromq.ZMQ.Socket)
  case class Line(processedMessage: ProcessedMessage) {
    override def toString() =
      "timestamp=" + processedMessage.tick.timestamp + ";" +
        "process=" + processedMessage.tick.subscription.process + ";" +
        "device=" + processedMessage.device + ";" +
        "power=" + processedMessage.energy.power
  }

  def process(processedMessage: ProcessedMessage) {
    println("Start processsing in Thrift reporter! ")
    val serializer = new TSerializer(new TBinaryProtocol.Factory())
    val messageobject = new Message()
    println("processedMessage.energy.power: ", processedMessage.energy.power)
    interval_index = interval_index + 1
    val mymap = Map("power"->(processedMessage.energy.power).toString,"type"->"powerapi","interval_index"->(interval_index).toString)
    val myjmap =  new java.util.HashMap[String,String](mymap) 
    println("mymap: ",myjmap)
    messageobject.content = myjmap
    val message = serializer.serialize(messageobject)
    val topic = "kmeans"
    publisher.send(topic.getBytes(),ZMQ.SNDMORE)
    publisher.send(message,0)
    println("Message sending is done!")
  }

}
