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
package fr.inria.powerapi.tool.sampling

import akka.actor.Actor
import fr.inria.powerapi.core.{ ProcessedMessage, Reporter, Tick }
import fr.inria.powerapi.sensor.libpfm.LibpfmCoreSensorMessage
import fr.inria.powerapi.reporter.file.FileReporter

import scalax.io.{ Resource, SeekableByteChannel }
import scalax.io.managed.SeekableByteChannelResource

object Util extends Configuration {
  // Method used to compute the median of any array type.
  def median[T](s: Seq[T])(implicit n: Fractional[T]) = {
    import n._
    val (lower, upper) = s.sortWith(_<_).splitAt(s.size / 2)
    if (s.size % 2 == 0) (lower.last + upper.head) / fromInt(2) else upper.head
  }
  
  // Allows to get all the available frequencies for the processor. All the core used the same frequencies so we used the first HT/Core see by the OS.
  def availableFrequencies: Option[Array[Long]] = {
    if(dvfs) {
      Some(scala.io.Source.fromFile(scalingFreqPath.replace("%?", "0")).mkString.trim.split(" ").map(_.toLong).sorted)
    }
    else None
  }
}

// Redirect the writes to the Writer actor.
class PowerspyReporter extends FileReporter with Configuration {
  override def process(processedMessage: ProcessedMessage) {
    context.system.eventStream.publish(LineToWrite(outPathPowerspy, processedMessage.energy.power + "\n"))
  }
}

case class LineToWrite(filename: String, str: String)

class Writer extends Actor with akka.actor.ActorLogging {
  val resources = scala.collection.mutable.HashMap[String, java.io.FileWriter]()

  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LineToWrite])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[LineToWrite])
    resources.foreach {
      case (_, writer) => writer.close()
    }
  }

  def receive() = {
    case line: LineToWrite => process(line)
    case _ => println("ooops ... the message cannot be handled!")
  }

  def process(line: LineToWrite) {
    if(!resources.contains(line.filename)) {
      val filewriter = try {
        new java.io.FileWriter(line.filename, true)
      }   
      catch {
        case e: java.io.IOException => if(log.isErrorEnabled) log.error("Oops, the Writer actor is not able to store the data in the givent files ..."); null
      }
      
      if(filewriter != null) {  
        resources += (line.filename -> filewriter)
      }
    }
    
    if(resources.contains(line.filename)) {
      resources(line.filename).write(line.str)
      resources(line.filename).flush()
    }
  }
}

/**
 * It is a specific component to handle directly the messages produce by the LibpfmCoreSensor component.
 */
class LibpfmListener extends Actor with Configuration {
  var timestamp = -1l
  val cache = scala.collection.mutable.HashMap[String, scala.collection.mutable.ListBuffer[LibpfmCoreSensorMessage]]()
 
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmCoreSensorMessage])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[LibpfmCoreSensorMessage])
    timestamp = -1l
    cache.clear()
  }

  case class Line(messages: List[LibpfmCoreSensorMessage]) {
    val aggregated = messages.foldLeft(0l)((acc, message) => acc + message.counter.value)
    override def toString() = s"$aggregated\n"
  }
  
  def addToCache(event: String, sensorMessage: LibpfmCoreSensorMessage) = {
    cache.get(event) match {
      case Some(buffer) => buffer += sensorMessage
      case None => {
        val buffer = scala.collection.mutable.ListBuffer[LibpfmCoreSensorMessage]()
        buffer += sensorMessage
        cache += (event -> buffer)
      }
    }
  }

  def receive() = {
    case sensorMessage: LibpfmCoreSensorMessage => process(sensorMessage)
  }

  def process(sensorMessage: LibpfmCoreSensorMessage) {
    if(timestamp == -1) timestamp = sensorMessage.tick.timestamp
    
    if(sensorMessage.tick.timestamp > timestamp) {
      cache.par.foreach {
        case (event, messages) => {
          context.system.eventStream.publish(LineToWrite(s"$outBasePathLibpfm$event.dat", Line(messages.toList).toString))
        }
      }
      timestamp = sensorMessage.tick.timestamp
      cache.clear()
    }

    addToCache(sensorMessage.event.name, sensorMessage)
  }
}
