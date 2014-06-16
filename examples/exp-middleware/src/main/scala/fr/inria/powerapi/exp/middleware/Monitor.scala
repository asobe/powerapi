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
package fr.inria.powerapi.exp.middleware

import fr.inria.powerapi.core.{ Configuration, Energy, Process, ProcessedMessage, Reporter, Tick, TickSubscription }
import fr.inria.powerapi.library.{ ALL, APPS, PAPI, PIDS }
import fr.inria.powerapi.sensor.libpfm.{ LibpfmUtil, SensorLibpfm, LibpfmSensorMessage, SensorLibpfmConfigured }
import fr.inria.powerapi.formula.libpfm.FormulaLibpfm
import fr.inria.powerapi.sensor.powerspy.SensorPowerspy
import fr.inria.powerapi.formula.powerspy.FormulaPowerspy
import fr.inria.powerapi.processor.aggregator.timestamp.{ TimestampAggregator, AggregatorTimestamp }
import fr.inria.powerapi.reporter.file.FileReporter
import fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter
import fr.inria.powerapi.reporter.console.ConsoleReporter

import scala.concurrent.duration.DurationInt
import scala.sys.process._
import scalax.file.Path
import scalax.file.ImplicitConversions.string2path
import scalax.io.{ Resource, SeekableByteChannel }
import scalax.io.managed.SeekableByteChannelResource

import akka.actor.{ Actor, Props }

/**
 * Part of extended components. It's used to add the idle power into the estimations.
 */
trait PowerIdleConfiguration extends Configuration {
  lazy val avgIdlePower = load {  _.getDouble("powerapi.libpfm.idle-power") } (0.0)
}

trait StressExpConfiguration extends Configuration {
  /** Thread numbers. */
  lazy val threads = load { _.getInt("powerapi.cpu.threads") }(0)
}

trait SpecExpConfiguration extends Configuration {
  lazy val specpath = load { _.getString("powerapi.spec.path") }("/home/powerapi/cpu2006")
}

case class AggregatedMessage(tick: Tick, device: String, messages: collection.mutable.Set[ProcessedMessage] = collection.mutable.Set[ProcessedMessage]()) 
extends PowerIdleConfiguration with ProcessedMessage {
  override def energy = {
    var energy = messages.foldLeft(0: Double) { (acc, message) => acc + message.energy.power }
    if(device == "cpu") {
      energy += avgIdlePower
    }
    Energy.fromPower(energy)
  }

  def add(message: ProcessedMessage) {
    messages += message
  }
  def +=(message: ProcessedMessage) {
    add(message)
  }
}

class ExtendedDeviceAggregator extends TimestampAggregator {
  def byDevices(implicit timestamp: Long): Iterable[AggregatedMessage] = {
    val base = cache(timestamp)
    val messages = collection.mutable.ArrayBuffer.empty[AggregatedMessage]

    for (byMonitoring <- base.messages.groupBy(_.tick.clockid)) {
      for (byDevice <- byMonitoring._2.groupBy(_.device)) {
        messages += AggregatedMessage(
          tick = Tick(byMonitoring._1, TickSubscription(byMonitoring._1, Process(-1), base.tick.subscription.duration), timestamp),
          device = byDevice._1,
          messages = byDevice._2
        )
      }
    }

    messages
  }

  override def send(implicit timestamp: Long) {
    byDevices foreach publish
  }
}

object AggregatorExtendedDevice extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[ExtendedDeviceAggregator]
}

trait AggregatorExtendedDevice {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorExtendedDevice)
}

class ExtendedFileReporter extends Reporter {
  case class Line(processedMessage: ProcessedMessage) {
    override def toString() = 
      "timestamp=" + processedMessage.tick.timestamp + ";" +
      "power=" + processedMessage.energy.power + scalax.io.Line.Terminators.NewLine.sep
  }

  override def process(processedMessage: ProcessedMessage) {
    Resource.fromFile("powerapi_" + processedMessage.device.toLowerCase + ".dat").append(Line(processedMessage).toString)
  }
}

object Tool {
  // Method used to compute the median of any array type.
  def median[T](s: Seq[T])(implicit n: Fractional[T]) = {
    import n._
    val (lower, upper) = s.sortWith(_<_).splitAt(s.size / 2)
    if (s.size % 2 == 0) (lower.last + upper.head) / fromInt(2) else upper.head
  }
}

/**
 * Object for simple experimentation, to observe the results directly in a chart.
 */
object Default {
  def run() = {
    LibpfmUtil.initialize()
    val libpfm = new PAPI with SensorLibpfm with FormulaLibpfm with AggregatorExtendedDevice
    val powerspy = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorExtendedDevice

    libpfm.start(1.seconds, ALL).attachReporter(classOf[JFreeChartReporter])
    powerspy.start(1.seconds, PIDS(-1)).attachReporter(classOf[JFreeChartReporter])
    
    Thread.sleep((5.hours).toMillis)
    
    powerspy.stop()
    libpfm.stop()
    
    LibpfmUtil.terminate()
  }
}

/**
 * Object for the experiments with SPEC CPU 2006.
 */
object SpecCPUExp extends SpecExpConfiguration{
  implicit val codec = scalax.io.Codec.UTF8
  val benchmarks = Array("calculix", "h264ref", "bwaves", "gamess", "milc", "hmmer", "wrf")
  val dataPath = "host-spec-cpu-data"
  val warmup = 0
  val nbRuns = warmup + 3
  val separator = "====="

  def collect() = {
    // Cleaning phase
    Path.fromString(dataPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    // Kill all the running benchmarks (if there is still alive from another execution).
    val benchsToKill = (Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "grep _base.amd64-m64-gcc43-nn") #> Seq("bash", "-c", "head -n 1") #> Seq("bash", "-c", "cut -d '/' -f 6") #> Seq("bash", "-c", "cut -d ' ' -f1")).lines
    benchsToKill.foreach(benchmark => Seq("bash", "-c", s"killall -s KILL specperl runspec specinvoke $benchmark &> /dev/null").run)

    // To be sure that the benchmarks are compiled, we launch the compilation before all the monitorings (no noise).
    benchmarks.foreach(benchmark => {
      val res = Seq("bash", "./src/main/resources/compile_bench.bash", specpath, benchmark).!
      if(res != 0) throw new RuntimeException("Umh, there is a problem with the compilation, maybe dependencies are missing.")
    })
    

    LibpfmUtil.initialize()
    val libpfm = new PAPI with SensorLibpfm with FormulaLibpfm with AggregatorExtendedDevice
    val powerspy = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorExtendedDevice

    for(run <- 1 to nbRuns) {
      benchmarks.foreach(benchmark => {
        val monitoringLibpfm = libpfm.start(1.seconds, ALL).attachReporter(classOf[ExtendedFileReporter])
        val monitoringPspy = powerspy.start(1.seconds, PIDS(-1)).attachReporter(classOf[ExtendedFileReporter])

        // Waiting for the synchronization.
        Thread.sleep((20.seconds).toMillis)
        (Path(".") * "*.dat").foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))

        // Launch the benchmark with a bash script (easiest way, and blocking).
        Seq("bash", "./src/main/resources/start_bench.bash", specpath, benchmark).!

        // For the moment, is the only way to stop powerapi.
        monitoringLibpfm.waitFor(1.milliseconds)
        monitoringPspy.waitFor(1.milliseconds)

        // Move files to the right place.
        s"$dataPath/$benchmark/$run".createDirectory(failIfExists=false)
        (Path(".") * "*.dat").foreach(path => {
          val name = path.name
          val target: Path = s"$dataPath/$benchmark/$run/$name"
          path.moveTo(target=target, replace=true)
        })
      })
    }

    powerspy.stop()
    libpfm.stop()

    LibpfmUtil.terminate()
  }

  def process() = {
    implicit val codec = scalax.io.Codec.UTF8

    lazy val PathRegex = (s"$dataPath" + """/\w+/(\d+)/.*""").r
    // Method used to sort the paths.
    def sortPaths(path1: Path, path2: Path) = {
      val nb1 = path1.path match {
        case PathRegex(benchmark) => benchmark
        case _ => ""
      }
      val nb2 = path2.path match {
        case PathRegex(benchmark) => benchmark
        case _ => ""
      }

      nb1.compareTo(nb2) < 0
    }

    val estimationPaths = (Path.fromString(dataPath) * """\w+""".r * """\d+""".r * "powerapi_cpu.dat")
    val powerPaths = (Path.fromString(dataPath) * """\w+""".r * """\d+""".r * "powerapi_powerspy.dat")

    // Get the data.
    val data = scala.collection.mutable.HashMap[Path, Array[String]]()
    estimationPaths.foreach(path => {
      data(path) = path.lines().toArray
    })
    val powers = scala.collection.mutable.HashMap[Path, Array[String]]()
    powerPaths.foreach(path => {
      powers(path) = path.lines().toArray  
    })

    val csvData = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()

    // Create the headers.
    csvData(0) = scala.collection.mutable.ArrayBuffer[String]()
    csvData(1) = scala.collection.mutable.ArrayBuffer[String]()

    benchmarks.sorted.foreach(elt => {
      csvData(0) += "# " + elt
      csvData(1) ++= Array("#Estimated", "# Measured", "#min", "#max")
    })

    val nbLinesDataCSV = csvData.keys.size

    for(benchmark <- benchmarks.sorted) {
      // Organize the data.
      val estimatedData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
      estimationPaths.toArray.filter(_.path.contains(benchmark)).sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
        var index = 0
        // Remove the synchronization phase
        data(path) = data(path).dropWhile(_ != separator).tail
        
        while(!data(path).isEmpty) {
          val existing = estimatedData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
          val stepPowers = data(path).takeWhile(_ != separator).filter(line => (line != "" && line != "0")).map(_.split("=").last.toDouble).filter(value => value > 0 && value < 1000)
          existing += stepPowers.sum
          estimatedData(index) = existing
          // tail is used to remove the separator.
          val tmpData = data(path).dropWhile(_ != separator)

          if(!tmpData.isEmpty) {
            data(path) = tmpData.tail
          }

          else data(path) = tmpData

          index += 1
        }
      })
      val powerspyData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
      powerPaths.toArray.filter(_.path.contains(benchmark)).sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
        var index = 0
        // Remove the synchronization phase
        powers(path) = powers(path).dropWhile(_ != separator).tail

        while(!powers(path).isEmpty) {
          val existing = powerspyData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
          val stepPowers = powers(path).takeWhile(_ != separator).filter(line => (line != "" && line != "0")).map(_.split("=").last.toDouble).filter(value => value > 0 && value < 1000)
          existing += stepPowers.sum
          powerspyData(index) = existing
          // tail is used to remove the separator.
          val tmpData = powers(path).dropWhile(_ != separator)

          if(!tmpData.isEmpty) {
            powers(path) = tmpData.tail
          }

          else powers(path) = tmpData

          index += 1
        }
      })

      for(i <- 0 until estimatedData.keys.size) {
        val consumption = Tool.median(estimatedData(i))
        val existing = csvData.getOrElse(nbLinesDataCSV + i, scala.collection.mutable.ArrayBuffer[String]())
        existing += consumption.toString
        csvData(nbLinesDataCSV + i) = existing
      }

      for(i <- 0 until powerspyData.keys.size) {
        val min = powerspyData(i).min
        val max = powerspyData(i).max
        val consumption = Tool.median(powerspyData(i))
        val existing = csvData.getOrElse(nbLinesDataCSV + i, scala.collection.mutable.ArrayBuffer[String]())
        existing += consumption.toString
        existing += min.toString
        existing += max.toString
        csvData(nbLinesDataCSV + i) = existing
      }
    }

    // Cleaning phase
    Path.fromString(s"$dataPath/chart.dat").delete()
    csvData.values.foreach(line => {
      Resource.fromFile(s"$dataPath/chart.dat").append(line.mkString(" ") + scalax.io.Line.Terminators.NewLine.sep)
    })
  }

  def run() = {
    collect()
    process()
  }
}

/**
 * Object used for the experiments with stress command, to show the non-linearity of complex processors.
 */
object StressExp extends StressExpConfiguration {
  val dataPath = "host-stress-data"
  val separator = "====="
  val duration = "30"
  val warmup = 0
  val nbRuns = warmup + 3
  
  def collect() = {
    Path.fromString(dataPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    Seq("bash", "-c", "killall stress &> /dev/null").run

    LibpfmUtil.initialize()
    val libpfm = new PAPI with SensorLibpfm with FormulaLibpfm with AggregatorExtendedDevice
    val powerspy = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorExtendedDevice

    for(run <- 1 to nbRuns) {
      var monitoringLibpfm = libpfm.start(1.seconds, ALL).attachReporter(classOf[ExtendedFileReporter])
      val monitoringPspy = powerspy.start(1.seconds, PIDS(-1)).attachReporter(classOf[ExtendedFileReporter])

      // Waiting for the synchronization and to get idle powers.
      Thread.sleep((40.seconds).toMillis)
      (Path(".") * "*.dat").foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))

      for(thread <- 1 to threads) {
        Seq("bash", "-c", s"stress -c $thread -t $duration").!
        (Path(".") * "*.dat").foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
      }

      // For the moment, is the only way to stop the monitoring.
      monitoringLibpfm.waitFor(1.milliseconds)
      monitoringPspy.waitFor(1.milliseconds)

     // Move files to the right place.
      s"$dataPath/$run".createDirectory(failIfExists=false)
      (Path(".") * "*.dat").foreach(path => {
        val name = path.name
        val target: Path = s"$dataPath/$run/$name"
        path.moveTo(target=target, replace=true)
      })
    }

    powerspy.stop()
    libpfm.stop()

    LibpfmUtil.terminate()
  }

  def process() = {
    implicit val codec = scalax.io.Codec.UTF8

    lazy val PathRegex = (s"$dataPath" + """/(\d)+/.*""").r
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

    val estimationPaths = (Path.fromString(dataPath) * """\d+""".r * "powerapi_cpu.dat")
    val powerPaths = (Path.fromString(dataPath) * """\d+""".r * "powerapi_powerspy.dat")

    // Get the data.
    val data = scala.collection.mutable.HashMap[Path, Array[String]]()
    estimationPaths.foreach(path => {
      data(path) = path.lines().toArray
    })
    val powers = scala.collection.mutable.HashMap[Path, Array[String]]()
    powerPaths.foreach(path => {
      powers(path) = path.lines().toArray  
    })

    val csvData = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()

    // Create the headers.
    csvData(0) = scala.collection.mutable.ArrayBuffer[String]()
    Array("# CPU Load", "# Estimated consumption", "# min median Pspy", "# max median Pspy").foreach(elt => csvData(0) += elt)

    val nbLinesDataCSV = csvData.keys.size

    // Organize the data.
    val estimatedData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
    estimationPaths.toArray.sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
      var index = 0
      while(!data(path).isEmpty) {
        val existing = estimatedData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
        existing ++= data(path).takeWhile(_ != separator).filter(line => (line != "" && line != "0")).map(_.split("=").last.toDouble).filter(value => value > 0 && value < 1000)
        estimatedData(index) = existing
        // tail is used to remove the separator.
        val tmpData = data(path).dropWhile(_ != separator)

        if(!tmpData.isEmpty) {
          data(path) = tmpData.tail
        }

        else data(path) = tmpData

        index += 1
      }
    })
    val powerspyData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
    powerPaths.toArray.sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
      var index = 0

      while(!powers(path).isEmpty) {
        val existing = powerspyData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
        val stepPowers = powers(path).takeWhile(_ != separator).filter(line => (line != "" && line != "0")).map(_.split("=").last.toDouble).filter(value => value > 0 && value < 1000)
        existing += Tool.median(stepPowers)
        powerspyData(index) = existing
        // tail is used to remove the separator.
        val tmpData = powers(path).dropWhile(_ != separator)

        if(!tmpData.isEmpty) {
          powers(path) = tmpData.tail
        }

        else powers(path) = tmpData

        index += 1
      }
    })

    // Compute the medians and store values inside the corresponding csv buffer.
    for(i <- 0 until estimatedData.keys.size) {
      val medianVal = Tool.median(estimatedData(i))
      val existing = csvData.getOrElse(nbLinesDataCSV + i, scala.collection.mutable.ArrayBuffer[String]())
      existing += (i.toDouble / (estimatedData.keys.size - 1)).toString
      existing += medianVal.toString
      csvData(nbLinesDataCSV + i) = existing
    }
    for(i <- 0 until powerspyData.keys.size) {
      var max = powerspyData(i).max
      var min = powerspyData(i).min
      val existing = csvData.getOrElse(nbLinesDataCSV + i, scala.collection.mutable.ArrayBuffer[String]())
      if(existing.size == 2) {
        val estimated = existing(1).toDouble
        min = (if (estimated - min > 0) min else estimated)
        max = (if (max - estimated > 0) max else estimated)
        existing += min.toString
        existing += max.toString
        csvData(nbLinesDataCSV + i) = existing
      }
    }

    // Cleaning phase
    Path.fromString(s"$dataPath/chart.dat").delete()
    csvData.values.foreach(line => {
      Resource.fromFile(s"$dataPath/chart.dat").append(line.mkString(" ") + scalax.io.Line.Terminators.NewLine.sep)
    })
  }

  def run() = {
    collect()
    process()
  }
}

// TODO: Remove the code. It's here for a testing purpose to analysis the counter evolutions.
object AnalysisCountersExp {
  class PowerspyReporter extends FileReporter {
    override lazy val filePath = "output-powerspy.dat"

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
  class LibpfmListener extends Actor {
    // Store all the streams to improve the speed processing.
    val resources = scala.collection.mutable.HashMap[String, SeekableByteChannelResource[SeekableByteChannel]]()
    
    override def preStart() = {
      context.system.eventStream.subscribe(self, classOf[LibpfmSensorMessage])
    }

    override def postStop() = {
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
        val output = Resource.fromFile(s"output-libpfm-$name.dat")
        resources += (name -> output)
        output
      }

      val output = resources.getOrElse(sensorMessage.event.name, updateResources(sensorMessage.event.name))
      output.append(Line(sensorMessage).toString)
    }
  }

  def run() = {
    implicit val codec = scalax.io.Codec.UTF8
    val separator = "===="
    val specpath = "/home/powerapi/cpu2006"
    val timestamp = System.nanoTime

    val benchmarks = scala.collection.mutable.ArrayBuffer[String]()
    // Float benchmarks
    benchmarks ++= Array("410.bwaves", "416.gamess", "433.milc", "434.zeusmp", "435.gromacs", "436.cactusADM", "437.leslie3d", "450.soplex", "453.povray", "454.calculix", "459.GemsFDTD", "465.tonto", "470.lbm")
    // Int benchmarks
    benchmarks ++= Array("481.wrf", "482.sphinx3", "400.perlbench", "401.bzip2", "403.gcc", "429.mcf", "445.gobmk", "456.hmmer", "458.sjeng", "462.libquantum", "464.h264ref", "471.omnetpp", "473.asta", "483.xalancbmk")
    // Kill all the running benchmarks (if there is still alive from another execution).
    val benchsToKill = (Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "grep _base.amd64-m64-gcc43-nn") #> Seq("bash", "-c", "head -n 1") #> Seq("bash", "-c", "cut -d '/' -f 6") #> Seq("bash", "-c", "cut -d ' ' -f1")).lines
    benchsToKill.foreach(benchmark => Seq("bash", "-c", s"killall -s KILL specperl runspec specinvoke $benchmark &> /dev/null").run)
    // To be sure that the benchmarks are compiled, we launch the compilation before all the monitorings (no noise).
    benchmarks.foreach(benchmark => {
      val res = Seq("bash", "./src/main/resources/compile_bench.bash", specpath, benchmark).!
      if(res != 0) throw new RuntimeException("Umh, there is a problem with the compilation, maybe dependencies are missing.")
    })

    val events = scala.collection.mutable.ArrayBuffer[String]()
    events ++= Array("PERF_COUNT_HW_CPU_CYCLES", "PERF_COUNT_HW_INSTRUCTIONS", "PERF_COUNT_HW_CACHE_REFERENCES", "PERF_COUNT_HW_CACHE_MISSES", "PERF_COUNT_HW_BRANCH_INSTRUCTIONS", "PERF_COUNT_HW_BRANCH_MISSES")
    events ++= Array("PERF_COUNT_HW_BUS_CYCLES", "PERF_COUNT_HW_STALLED_CYCLES_FRONTEND", "PERF_COUNT_HW_STALLED_CYCLES_BACKEND", "PERF_COUNT_HW_REF_CPU_CYCLES")

    LibpfmUtil.initialize()

    val powerapi = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorTimestamp
    // One libpfm sensor per event.
    events.distinct.foreach(event => powerapi.configure(new SensorLibpfmConfigured(event)))

    for(benchmark <- benchmarks) {
      // Cleaning phase
      (Path(".") * "*.dat").foreach(path => path.delete(force = true))
      // Kill all the running benchmarks (if there is still alive from another execution).
      val benchsToKill = (Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "grep _base.amd64-m64-gcc43-nn") #> Seq("bash", "-c", "head -n 1") #> Seq("bash", "-c", "cut -d '/' -f 6") #> Seq("bash", "-c", "cut -d ' ' -f1")).lines
      benchsToKill.foreach(benchmark => Seq("bash", "-c", s"killall -s KILL specperl runspec specinvoke $benchmark &> /dev/null").run)

      // Start a monitoring to get the idle power.
      // We add some time because of the sync. between PowerAPI & PowerSPY.
      powerapi.start(1.seconds, PIDS(-1)).attachReporter(classOf[PowerspyReporter]).waitFor(20.seconds)
      Resource.fromFile("output-powerspy.dat").append(separator + scalax.io.Line.Terminators.NewLine.sep)

      // Start the libpfm sensor message listener to intercept the LibpfmSensorMessage.
      val libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])

       // Launch stress command to stimulate all the features on the processor.
      // Here, we used a specific bash script to be sure that the command in not launch before to open and reset the counters.
      val buffer = Seq("bash", "./src/main/resources/start.bash", s"./src/main/resources/start_bench_test.bash $specpath $benchmark").lines
      val ppid = buffer(0).trim.toInt

      // Start a monitoring to get the values of the counters for the workload.
      val monitoring = powerapi.start(1.seconds, PIDS(ppid)).attachReporter(classOf[PowerspyReporter])
      Seq("kill", "-SIGCONT", ppid+"").!

      while(Seq("kill", "-0", ppid+"").! == 0) {
        Thread.sleep((1.minutes).toMillis)
      }

      monitoring.waitFor(1.milliseconds)

      powerapi.system.stop(libpfmListener)

      // Move files to the right place, to save them for the future regression.
      s"$timestamp/$benchmark".createDirectory(failIfExists=false)
      (Path(".") * "*.dat").foreach(path => {
        val name = path.name
        val target: Path = s"$timestamp/$benchmark/$name"
        path.moveTo(target=target, replace=true)
      })
    }

    powerapi.stop()
    LibpfmUtil.terminate()
  }
}

// Object launcher.
object Monitor extends App {
  Default.run()
  //SpecCPUExp.run()
  //StressExp.run()
  //AnalysisCountersExp.run()
  System.exit(0)
}