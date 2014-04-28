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

import fr.inria.powerapi.core.{ ProcessedMessage, Reporter }
import fr.inria.powerapi.library.{ PAPI, PIDS }
import fr.inria.powerapi.sensor.powerspy.SensorPowerspy
import fr.inria.powerapi.formula.powerspy.FormulaPowerspy
import fr.inria.powerapi.sensor.libpfm.{ LibpfmSensorMessage, LibpfmUtil, SensorLibpfm, SensorLibpfmConfigured }
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp
import fr.inria.powerapi.reporter.file.FileReporter

import akka.actor.{ Actor, Props }

import scala.concurrent.duration.DurationInt
import scala.sys.process._

import scalax.file.Path
import scalax.file.ImplicitConversions.string2path
import scalax.io.{ Resource, SeekableByteChannel }
import scalax.io.managed.SeekableByteChannelResource

import com.typesafe.config.Config

import nak.regress.LinearRegression
import breeze.linalg._

trait Configuration extends fr.inria.powerapi.core.Configuration with fr.inria.powerapi.sensor.libpfm.Configuration {
  /** Core numbers. */
  lazy val threads = load { _.getInt("powerapi.tool.sampling.threads") }(0)
  /** Cache available (all levels, only for the data) in KB. */
  lazy val l3Cache = load { _.getInt("powerapi.tool.sampling.L3-cache") }(0)
  /** Number of samples .*/
  lazy val samples = load { _.getInt("powerapi.tool.sampling.samples") }(0)
  /** Number of required messages per step. */
  lazy val nbMessages = load { _.getInt("powerapi.tool.sampling.step.messages") }(15)
  /** Path used to store the files created during the sampling. */
  lazy val samplingPath = load { _.getString("powerapi.tool.sampling.path") }("samples")
  /** Path used to store the processed files, used to compute the final formulae. */
  lazy val processingPath = load { _.getString("powerapi.tool.processing.path") }("pr-data")
  /** Path used to store the formulae computed by a multiple linear regression. */
  lazy val formulaePath = load { _.getString("powerapi.tool.formulae.path") }("formulae")

  /**
   * Scaling frequencies information, giving information about the available frequencies for each core.
   * This information is typically given by the cpufrequtils utils.
   *
   * @see http://www.kernel.org/pub/linux/utils/kernel/cpufreq/cpufreq-info.html
   */
  lazy val scalingFreqPath = load { _.getString("powerapi.tool.sampling.scaling-available-frequencies") }("/sys/devices/system/cpu/cpu%?/cpufreq/scaling_available_frequencies")
  /** Default values for the output files. */
  lazy val outBasePathLibpfm = "output-libpfm-"
  lazy val outPathPowerspy = "output-powerspy.dat"
  lazy val separator = "======="
  /** Default values for data processing. */
  lazy val elements = Array("cpu", "cache")
  lazy val eltIdlePower = "cpu"
  lazy val csvDelimiter = ";"
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
    context.system.eventStream.subscribe(self, classOf[LibpfmSensorMessage])
  }

  override def postStop() = {
    context.system.eventStream.unsubscribe(self, classOf[LibpfmSensorMessage])
    resources.clear()
  }

  case class Line(sensorMessage: LibpfmSensorMessage) {
    val counter = sensorMessage.counter.value
    val newLine = scalax.io.Line.Terminators.NewLine.sep
    
    override def toString() = s"$counter$newLine"
  }

  def receive() = {
    case sensorMessage: LibpfmSensorMessage => process(sensorMessage)
  }

  def process(sensorMessage: LibpfmSensorMessage) {
    def updateResources(name: String): SeekableByteChannelResource[SeekableByteChannel] = {
      val output = Resource.fromFile(s"$outBasePathLibpfm$name.dat")
      resources += (name -> output)
      output
    }

    val output = resources.getOrElse(sensorMessage.event.name, updateResources(sensorMessage.event.name))
    output.append(Line(sensorMessage).toString)
  }
}

/**
 * Main launcher.
 */
object SamplingTool extends App {
  //new Sampling().run()
  //new Processing().run()
  new MultipleLinearRegression().run()
  System.exit(0)
}

/** 
 * Allows to run the sampling step (collect the data related to several stress).
 * Be careful, we need the root access to write in sys virtual filesystem, else, we can not control the frequency.
 */
class Sampling extends Configuration {
  def run() = {
    implicit val codec = scalax.io.Codec.UTF8
    val availableFreqs = scala.collection.mutable.SortedSet[Long]()
    val pathMatcher = s"$outBasePathLibpfm*.dat"
    val base = 8

    LibpfmUtil.initialize()

    // Get the available frequencies from sys virtual filesystem.
    (for(thread <- 0 until threads) yield (scalingFreqPath.replace("%?", thread.toString))).foreach(filepath => {
      availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
    })

    // Cleaning phase
    Path.fromString(samplingPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    val powerapi = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorTimestamp
    // One libpfm sensor per event.
    events.distinct.foreach(event => powerapi.configure(new SensorLibpfmConfigured(event, bitset)))

    for(index <- 1 to samples) {
      for(frequency <- availableFreqs) {
        // Set the default governor with the userspace governor. It allows us to control the frequency.
        Seq("bash", "-c", "echo userspace | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
        // Set the frequency
        Seq("bash", "-c", s"echo $frequency | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_setspeed > /dev/null").!

        // To be sure that the frequency is set by the processor.
        Thread.sleep((2.seconds).toMillis)
        
        // Start a monitoring to get the idle power.
        // We add some time because of the sync. between PowerAPI & PowerSPY.
        powerapi.start(PIDS(-1), 1.seconds).attachReporter(classOf[PowerspyReporter]).waitFor(nbMessages.seconds + 10.seconds)
        Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)

        // Start the libpfm sensor message listener to intercept the LibpfmSensorMessage.
        val libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])
        
        // Stress only the cpu, without caches.
        for(thread <- 1 to threads) {
          // Launch stress command to stimulate all the features on the processor.
          // Here, we used a specific bash script to be sure that the command in not launch before to open and reset the counters.
          val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -c $thread -t $nbMessages").lines
          val ppid = buffer(0).trim.toInt

          // Start a monitoring to get the values of the counters for the workload.
          val monitoring = powerapi.start(PIDS(ppid), 1.seconds).attachReporter(classOf[PowerspyReporter])
          Seq("kill", "-SIGCONT", ppid+"").!
          monitoring.waitFor(nbMessages.seconds)

          (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
          Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
        }

        // Move files to the right place, to save them for the future regression.
        s"$samplingPath/$index/$frequency/cpu".createDirectory(failIfExists=false)
        (Path(".") * "*.dat").foreach(path => {
          val name = path.name
          val target: Path = s"$samplingPath/$index/$frequency/cpu/$name"
          path.moveTo(target=target, replace=true)
        })

        // We stress only one core (we consider that the environment is heterogeneous).
        for(kbytes <- Iterator.iterate(1)(_ * base).takeWhile(_ < l3Cache)) {
          val bytes = kbytes * 1024
          // Launch stress command to stimulate the available cache on the processor.
          val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -m 1 --vm-bytes $bytes -t $nbMessages").lines
          val ppid = buffer(0).trim.toInt
          // Pin the process on the first core (physical or logical).
          Seq("taskset", "-cp", "0", ppid+"").lines

          val monitoring = powerapi.start(PIDS(ppid), 1.seconds).attachReporter(classOf[PowerspyReporter])
          Seq("kill", "-SIGCONT", ppid+"").!
          monitoring.waitFor(nbMessages.seconds)
          
          (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
          Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
        }

        if(l3Cache > 0 && ((math.log(l3Cache) / math.log(base)) % base) != 0) {
          // Last stress with all the cache memory.
          val bytes = l3Cache * 1024
          val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -m 1 --vm-bytes $bytes -t $nbMessages").lines
          val ppid = buffer(0).trim.toInt
          Seq("taskset", "-cp", "0", ppid+"").lines

          val monitoring = powerapi.start(PIDS(ppid), 1.seconds).attachReporter(classOf[PowerspyReporter])
          Seq("kill", "-SIGCONT", ppid+"").!
          monitoring.waitFor(nbMessages.seconds)
          
          (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
          Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
        }

        // Move files to the right place, to save them for the future regression.
        s"$samplingPath/$index/$frequency/cache".createDirectory(failIfExists=false)
        (Path(".") * "*.dat").foreach(path => {
          val name = path.name
          val target: Path = s"$samplingPath/$index/$frequency/cache/$name"
          path.moveTo(target=target, replace=true)
        })

        powerapi.system.stop(libpfmListener)
      }
    }

    // Reset the governor with the ondemand policy.
    Seq("bash", "-c", "echo ondemand | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
    powerapi.stop()
    LibpfmUtil.terminate()
  }
}

/**
 * Allows to process the data collected and create the csv files used during the regression step.
 */
class Processing extends Configuration {
  def run() = {
    // Method used to compute the median of any array type.
    def median[T](s: Seq[T])(implicit n: Fractional[T]) = {
      import n._
      val (lower, upper) = s.sortWith(_<_).splitAt(s.size / 2)
      if (s.size % 2 == 0) (lower.last + upper.head) / fromInt(2) else upper.head
    }

    lazy val PathRegex = (s"$samplingPath" + """/(\d)+/.*""").r
    // Method used to sort the paths.
    def sortPaths(path1: Path, path2: Path) = {
      val nb1 = path1.path match {
        case PathRegex(nb) => nb.toDouble
        case _ => 0.0
      }
      val nb2 = path2.path match {
        case PathRegex(nb) => nb.toDouble
        case _ => 0.0
      }

      nb1.compareTo(nb2) < 0
    }

    implicit val codec = scalax.io.Codec.UTF8
    val availableFreqs = scala.collection.mutable.SortedSet[Long]()

    // Cleaning phase
    Path.fromString(processingPath).deleteRecursively(force = true)

    if(!Path.fromString(samplingPath).exists) System.exit(0)
    
    // Get the available frequencies from sys virtual filesystem.
    (for(thread <- 0 until threads) yield (scalingFreqPath.replace("%?", thread.toString))).foreach(filepath => {
      availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
    })

    for(frequency <- availableFreqs) {
      // Used to build the csv arrays which be used to write the corresponding file.
      val csvData = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()
      val csvIdlePower = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()
      val csvPowers = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()

      // Create the headers.
      csvData(0) = scala.collection.mutable.ArrayBuffer[String]()
      events.distinct.sorted.foreach(event => csvData(0) += s"$event (median)")
      csvPowers(0) = scala.collection.mutable.ArrayBuffer[String]("P (median)")
      csvIdlePower(0) = scala.collection.mutable.ArrayBuffer[String]("P (median)")
      
      // Loop on the stressed elements.
      for(elt <- elements) {
        // Each file corresponds to one event.
        val eventsPaths = (Path.fromString(samplingPath) * """\d+""".r * frequency.toString * elt * s"$outBasePathLibpfm*.dat")
        val powersPaths = (Path.fromString(samplingPath) * """\d+""".r * frequency.toString * elt * outPathPowerspy)

        // Get the data.
        val data = scala.collection.mutable.HashMap[Path, Array[String]]()
        eventsPaths.foreach(path => {
          data(path) = path.lines().toArray
        })
        val powers = scala.collection.mutable.HashMap[Path, Array[String]]()
        powersPaths.foreach(path => {
          powers(path) = path.lines().toArray  
        })

        val nbLinesDataCSV = csvData.keys.size
        val nbLinesPowersCSV = csvPowers.keys.size

        // PART 1: Compute the medians for each event.
        for(event <- events.distinct.sorted) {
          // Organize the data.
          val eventData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
          eventsPaths.filter(_.path.endsWith(s"$event.dat")).toArray.sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
            var index = 0
            while(!data(path).isEmpty) {
              val existing = eventData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
              existing ++= data(path).takeWhile(_ != separator).filter(line => line != "" && line != "0").map(_.toDouble)
              eventData(index) = existing
              // tail is used to remove the separator.
              data(path) = data(path).dropWhile(_ != separator).tail
              index += 1
            }
          })
         
          // Compute the medians and store values inside the corresponding csv buffer.
          for(i <- 0 until eventData.keys.size) {
            // +1 because of the header
            val medianVal = median(eventData(i))
            val line = csvData.getOrElse(nbLinesDataCSV + i, scala.collection.mutable.ArrayBuffer[String]())
            line += medianVal.toLong.toString
            csvData(nbLinesDataCSV + i) = line
          }
        }
        // END PART 1

        // PART 2: Processed the powerspy files.
        val idlePowersData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
        val powersData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
        
        powersPaths.toArray.sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
          // Special case for the file wich contains the idle power. The values will be store in another buffer.
          if(path.path.endsWith(s"$eltIdlePower/$outPathPowerspy")) {
            val existing = idlePowersData.getOrElse(0, scala.collection.mutable.ArrayBuffer[Double]())
            existing ++= powers(path).takeWhile(_ != separator).filter(line => line != "" && line != "0").map(_.toDouble)
            idlePowersData(0) = existing
            // tail is used to remove the separator.
            powers(path) = powers(path).dropWhile(_ != separator).tail
          }
          var index = 0
          while(!powers(path).isEmpty) {
            val existing = powersData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
            existing ++= powers(path).takeWhile(_ != separator).filter(line => line != "" && line != "0").map(_.toDouble)
            powersData(index) = existing
            // tail is used to remove the separator.
            powers(path) = powers(path).dropWhile(_ != separator).tail
            index += 1
          }
        })

        // Compute the medians and store values inside the corresponding csv buffer.
        if(!idlePowersData.isEmpty) {
          val medianVal = median(idlePowersData(0))
          val line = csvIdlePower.getOrElse(1, scala.collection.mutable.ArrayBuffer[String]())
          line += medianVal.toDouble.toString
          csvIdlePower(1) = line
        }

        for(i <- 0 until powersData.keys.size) {
          // +1 because of the header
          val medianVal = median(powersData(i))
          val line = csvPowers.getOrElse(nbLinesPowersCSV + i, scala.collection.mutable.ArrayBuffer[String]())
          line += medianVal.toDouble.toString
          csvPowers(nbLinesPowersCSV + i) = line
        }
        // END PART 2
      }

      // Write the corresponding csv files in a dedicated directory.
      s"$processingPath/$frequency".createDirectory(failIfExists=false)

      csvIdlePower.values.foreach(line => {
        Resource.fromFile(s"$processingPath/$frequency/idle.csv").append(line.mkString(csvDelimiter) + scalax.io.Line.Terminators.NewLine.sep)
      })
      csvPowers.values.foreach(line => {
        Resource.fromFile(s"$processingPath/$frequency/powers.csv").append(line.mkString(csvDelimiter) + scalax.io.Line.Terminators.NewLine.sep)
      })
      csvData.values.foreach(line => {
        Resource.fromFile(s"$processingPath/$frequency/counters.csv").append(line.mkString(csvDelimiter) + scalax.io.Line.Terminators.NewLine.sep)
      })
    }
  }
}

/**
 * Allows to compute the formulae related to the frequency, hardware counters and powers. They are written into a unique file.
 */
class MultipleLinearRegression extends Configuration {
  def run() = {
    implicit val codec = scalax.io.Codec.UTF8
    val availableFreqs = scala.collection.mutable.SortedSet[Long]()

    // Cleaning phase
    Path.fromString(formulaePath).deleteRecursively(force = true)

    if(!Path.fromString(processingPath).exists) System.exit(0)
    
    // Get the available frequencies from sys virtual filesystem.
    (for(thread <- 0 until threads) yield (scalingFreqPath.replace("%?", thread.toString))).foreach(filepath => {
      availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
    })

    var formattedString = "powerapi.libpfm.formulae = [" + scalax.io.Line.Terminators.NewLine.sep
    
    for(frequency <- availableFreqs) {
      val counters = csvread(file = new java.io.File(s"$processingPath/$frequency/counters.csv"), 
        separator = csvDelimiter.charAt(0),
        skipLines = 1)
      val ones = DenseMatrix.ones[Double](counters.rows, 1)
      val data = DenseMatrix.horzcat(counters, ones)
      val powers = csvread(file = new java.io.File(s"$processingPath/$frequency/powers.csv"),
        separator = csvDelimiter.charAt(0),
        skipLines = 1)
      val coefficients = LinearRegression.regress(data, powers(::, 0))
      
      formattedString += "\t{freq = " + frequency.toString + ", formula = [" + coefficients.toArray.mkString(",") + "]}" + scalax.io.Line.Terminators.NewLine.sep
    }

    formattedString += "]"
    Resource.fromFile(s"$formulaePath/libpfm-formula.conf").append(formattedString)
  }
}