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

import java.io.File

import scala.concurrent.duration.DurationInt
import scalax.io.Resource

import com.typesafe.config.ConfigFactory

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.core.Reporter
import fr.inria.powerapi.library.PowerAPI
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator


/**
 * Listen to ProcessedMessage and display its content into a given file.
 *
 * @author lhuertas
 */
class FileReporter extends Reporter {

  case class Line(processedMessage: ProcessedMessage) {
    override def toString() = {
      processedMessage.energy.power + scalax.io.Line.Terminators.NewLine.sep
    }
  }

  def process(processedMessage: ProcessedMessage) {
    Resource.fromFile("powerapi-sampling").append(Line(processedMessage).toString)
  }
}

/**
 * Sample the data provided by PowerSpy
 *
 * @author lhuertas
 */
object Sampling {
  //Data sampling configuration part
  lazy val conf = ConfigFactory.load
  
  lazy val nbCore = conf.getInt("powerapi.cpu.core")
  
  //Number of message returned by PowerSpy required for the computation of the power average
  lazy val nbMessage = conf.getInt("powerapi.tool.sampling.message.count")
  
  //The increase of the stress activity at each step
  lazy val stressActivityStep = conf.getInt("powerapi.tool.sampling.stress.activity-step")

  def start() {
    Runtime.getRuntime.exec(Array("rm", "powerapi-sampling"))
    val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt
    PowerAPI.startMonitoring(
      process = Process(currentPid),
      duration = 1.second,
      processor = classOf[TimestampAggregator],
      listener = classOf[FileReporter]
    )
    
    var stressPID = ""
    var curStressActivity = 100.0
    var curCPUActivity    = 0.0
    var step   = 1
    val nbStep = nbCore*(100/stressActivityStep).toInt
    val samplingFile = new File("powerapi-sampling")
    
    while (!samplingFile.isFile()) {
      Thread.sleep((1.second).toMillis)
    }
    
    Thread.sleep(((nbMessage-1).second).toMillis)
    
    while (step <= nbStep) {

      if (curStressActivity >= 100.0) {
        Runtime.getRuntime.exec(Array("stress", "-v", "-c", "1"))
        stressPID = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", "stress", "ho", "pid")).getInputStream).lines().last
        curStressActivity = 0.0
      }
      
      curStressActivity += stressActivityStep
      val cpulimitPIDs = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", "cpulimit", "ho", "pid")).getInputStream).lines()
      if (cpulimitPIDs.size > 0) {
        Runtime.getRuntime.exec(Array("kill", "-9", cpulimitPIDs(0).toString))
      }
      Runtime.getRuntime.exec(Array("cpulimit", "-l", curStressActivity.toString, "-p", stressPID))
      curCPUActivity += 100.0 / nbStep
      
      step += 1
      
      Thread.sleep((nbMessage.second).toMillis)
    }
    
    PowerAPI.stopMonitoring(
      process = Process(currentPid),
      duration = 1.second,
      processor = classOf[TimestampAggregator],
      listener = classOf[FileReporter]
    )
    Runtime.getRuntime.exec(Array("killall", "stress"))
  }
}
