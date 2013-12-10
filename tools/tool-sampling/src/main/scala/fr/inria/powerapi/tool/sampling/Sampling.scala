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

import scala.concurrent.duration.DurationInt
import scalax.io.Resource
import scalax.file.Path

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigException

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.ProcessedMessage
import fr.inria.powerapi.core.Reporter
import fr.inria.powerapi.library.PowerAPI
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator

/**
 * Sampling's configuration part
 *
 * @author mcolmant
 */
trait SamplingConfiguration {
  /**
   * Link to get information from configuration files.
   */
  private lazy val conf = ConfigFactory.load

  // No default value, required value
  lazy val nbCore = load(_.getInt("powerapi.cpu.core"))(0)
  // Samples directory
  lazy val samplesDirPath = load(_.getString("powerapi.tool.sampling.path"))("samples/")
  // Sampling step count
  lazy val nbSamples = load(_.getInt("powerapi.tool.sampling.count"))(4)
  // Correlation coefficient
  lazy val corrCoeff = load(_.getDouble("powerapi.tool.sampling.corr_coeff"))(0.998)
  // PowerSpy messages number, used to improve the sampling accuracy
  lazy val nbMessage = load(_.getInt("powerapi.tool.sampling.message.count"))(10)
  // Stress activity increase
  lazy val stressActivityStep = load(_.getInt("powerapi.tool.sampling.stress.activity-step"))(25)
  
  lazy val filePath = samplesDirPath + "powerapi_sampling.dat"
  lazy val output = {
    Resource.fromFile(filePath)
  }
  lazy val nbStep = nbCore * (100 / stressActivityStep).toInt
  lazy val separator = "===="

  /**
   * Default pattern to get information from configuration file.
   *
   * @param request: request to get information from configuration file.
   * @param default: default value returned in case of ConfigException.
   *
   * @see http://typesafehub.github.com/config/latest/api/com/typesafe/config/ConfigException.html
   */
  def load[T](request: Config => T)(default: T): T =
    try {
      request(conf)
    } catch {
      case ce: ConfigException => {
        default
      }
    }
}

/**
 * Listen to ProcessedMessage and display its content into a given file.
 *
 * @author lhuertas
 * @author mcolmant
 */
class FileReporter extends Reporter with SamplingConfiguration {
  case class Line(processedMessage: ProcessedMessage) {
    override def toString() = {
      processedMessage.energy.power + scalax.io.Line.Terminators.NewLine.sep
    }
  }

  def process(processedMessage: ProcessedMessage) {
    output.append(Line(processedMessage).toString)
  }
}

/**
 * Sample the data provided by PowerSpy
 *
 * @author lhuertas
 */
object Sampling extends SamplingConfiguration {
  
  def start() {
    // Cleaning phase
    Path.fromString(samplesDirPath).deleteRecursively(force = true)

    val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

    // One monitoring per samling, it allows to avoid the noise and to have the right messages number
    for(sample <- 1 to nbSamples) {
      var stressPID = ""
      var curStressActivity = 100.0
      var curCPUActivity = 0.0

      PowerAPI.startMonitoring(
        process = Process(currentPid),
        duration = 1.second,
        processor = classOf[TimestampAggregator],
        listener = classOf[FileReporter]
      )
      
      // We use a separator between each step
      // Initialization step, waiting the syncronization between PowerAPI and PowerSPY
      Thread.sleep((30.second).toMillis)
      output.append(separator + scalax.io.Line.Terminators.NewLine.sep)

      // Idle sampling
      Thread.sleep(((nbMessage).second).toMillis)
      output.append(separator + scalax.io.Line.Terminators.NewLine.sep)
      
      // Use stress and cpulimit commands to get all cpu features
      for(step <- 1 to nbStep) {
        if (curStressActivity >= 100.0) {
          Runtime.getRuntime.exec(Array("stress", "-v", "-c", "1"))
          stressPID = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", "stress", "ho", "pid")).getInputStream).lines().last
          curStressActivity = 0.0
        }
        
        curStressActivity += stressActivityStep
        var cpulimitPIDs = Resource.fromInputStream(Runtime.getRuntime.exec(Array("ps", "-C", "cpulimit", "ho", "pid")).getInputStream).lines()
        
        if (cpulimitPIDs.size > 0) {
          Runtime.getRuntime.exec(Array("kill", "-9", cpulimitPIDs(0).toString))
        }
        
        curCPUActivity += 100.0 / nbStep
        Runtime.getRuntime.exec(Array("cpulimit", "-l", curStressActivity.toString, "-p", stressPID))
        Thread.sleep((nbMessage.second).toMillis)
        output.append(separator + scalax.io.Line.Terminators.NewLine.sep)
      }

      PowerAPI.stopMonitoring(
        process = Process(currentPid),
        duration = 1.second,
        processor = classOf[TimestampAggregator],
        listener = classOf[FileReporter]
      )

      Runtime.getRuntime.exec(Array("killall", "stress"))

      // Backup
      Path.fromString(filePath).copyTo(Path.fromString(samplesDirPath + "sample_" + sample + ".dat"))
      Path.fromString(filePath).deleteIfExists()
    }
  }
}