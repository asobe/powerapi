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
import fr.inria.powerapi.core.{ ProcessedMessage, Reporter }
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
    if(cpuFreq) {
      Some(scala.io.Source.fromFile(scalingFreqPath.replace("%?", "0")).mkString.trim.split(" ").map(_.toLong).sorted)
    }
    else None
  }

  // Used to get the processor topology.
  def topology: Map[Int, Array[Int]] = {
     // TODO: refactor to use hwloc
    Map(0 -> Array(0,4), 1 -> Array(1,5), 2 -> Array(2,6), 3 -> Array(3,7))
  }
}

class PowerspyReporter extends FileReporter with Configuration {
  override lazy val filePath = outPathPowerspy

  override def process(processedMessage: ProcessedMessage) {
    val power = processedMessage.energy.power
    val newLine = scalax.io.Line.Terminators.NewLine.sep
    output.append(s"$power$newLine")
  }
}

/**
 * It is a specific component to handle directly the messages produce by the LibpfmSensor.
 * We just want to write the counter values into a file.
 */
class LibpfmListener extends Actor with Configuration {
  // Store all the streams to improve the speed processing.
  val resources = scala.collection.mutable.HashMap[String, SeekableByteChannelResource[SeekableByteChannel]]()
  
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmCoreSensorMessage])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[LibpfmCoreSensorMessage])
    resources.clear()
  }

  case class Line(sensorMessage: LibpfmCoreSensorMessage) {
    val counter = sensorMessage.counter.value
    val newLine = scalax.io.Line.Terminators.NewLine.sep
    
    override def toString() = s"$counter$newLine"
  }

  def receive() = {
    case sensorMessage: LibpfmCoreSensorMessage => process(sensorMessage)
  }

  def process(sensorMessage: LibpfmCoreSensorMessage) {
    def updateResources(name: String, osIndex: Int): SeekableByteChannelResource[SeekableByteChannel] = {
      val output = Resource.fromFile(s"$outBasePathLibpfm$name-$osIndex.dat")
      resources += (name + "-" + osIndex -> output)
      output
    }

    val output = resources.getOrElse(sensorMessage.event.name + "-" + sensorMessage.core.id, updateResources(sensorMessage.event.name, sensorMessage.core.id))
    output.append(Line(sensorMessage).toString)
  }
}
