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

import fr.inria.powerapi.library.{ PAPI, PIDS }
import fr.inria.powerapi.sensor.powerspy.SensorPowerspy
import fr.inria.powerapi.formula.powerspy.FormulaPowerspy
import fr.inria.powerapi.sensor.libpfm.{ LibpfmUtil, SensorLibpfmConfigured }
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp

import akka.actor.{ ActorRef, Props }

import scala.concurrent.duration.DurationInt
import scala.sys.process._

import scalax.file.Path
import scalax.file.ImplicitConversions.string2path
import scalax.io.Resource

/** 
 * Allows to run the sampling step (collect the data related to several stress) by following the PPID and use the inherit option.
 * Be careful, we need the root access to write in sys virtual filesystem, else, we can not control the frequency.
 */
object Sampling extends Configuration {
  val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  var powerapi: fr.inria.powerapi.library.PAPI = null
  // thread -> (governor, frequency)
  val backup = scala.collection.mutable.HashMap[String, (String, Long)]()
  for(thread <- 0 until threads) {
    val governor = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_governor").lines.toArray.apply(0)
    val frequency = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_setspeed").lines.toArray.apply(0)
    backup += (thread.toString -> (governor, fr.inria.powerapi.library.Util.stringToLong(frequency).getOrElse(-1l)))
  }

  val shutdownThread = scala.sys.ShutdownHookThread {
    println("\nPowerAPI is going to shutdown ...")
    
    if(powerapi != null) {
      powerapi.stop
      LibpfmUtil.terminate()
    }

    backup.foreach {
      case(thread, (governor, frequency)) => {
        Seq("bash", "-c", s"echo $governor > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_governor").!

        if(frequency != -1) {
          Seq("bash", "-c", s"echo $frequency > /sys/devices/system/cpu/cpu0/cpufreq/scaling_setspeed").!
        }
      }
    }
  }

  private def sampling(index: Int, frequency: Long) = {
    implicit val codec = scalax.io.Codec.UTF8
    val outputs = scala.collection.mutable.ListBuffer[String]()
    val logger = ProcessLogger(out => outputs += out, err => {})
    val pathMatcher = s"$outBasePathLibpfm*.dat"
    val base = 8

    // To be sure that the frequency is set by the processor.
    Thread.sleep((2.seconds).toMillis)
    
    // Start a monitoring to get the idle power.
    // We add some time because of the sync. between PowerAPI & PowerSPY.
    powerapi.start(1.seconds, PIDS(currentPid)).attachReporter(classOf[PowerspyReporter]).waitFor(nbMessages.seconds + 10.seconds)
    Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)

    // Start the libpfm sensor message listener to intercept the LibpfmSensorMessage.
    val libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])
    val threadIds = scala.collection.mutable.ArrayBuffer[String]()

    // Stress only the processor, without cache (full core load).
    for(thread <- 1 to threads) {
      threadIds += (thread - 1).toString
      // Launch stress command to stimulate all the features on the processor.
      // Here, we used a specific bash script to be sure that the command in not launch before to open and reset the counters.
      val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -c $thread -t $nbMessages").lines
      val ppid = buffer(0).trim.toInt
      //Seq("taskset", "-cp", threadIds.mkString(","), ppid.toString).run(logger)
      //outputs.clear

      // Start a monitoring to get the values of the counters for the workload.
      val monitoring = powerapi.start(1.seconds, PIDS(ppid)).attachReporter(classOf[PowerspyReporter])
      Seq("kill", "-SIGCONT", ppid+"").!
      
      // Get the worker pid corresponding to the stress. 
      while(outputs.size < 1) { 
        Seq("pgrep", "stress").!(logger) 
      }
      
      outputs -= ppid.toString
      for(tmpThread <- 0 until thread) {
        Seq("taskset", "-cp", tmpThread.toString, outputs(tmpThread)).run
      }
      outputs.clear

      monitoring.waitFor(nbMessages.seconds)

      (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
      Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
    }

    threadIds.clear

    // Stress only the processor, without cache (decreasing load).
    /*val nbSec = (100 / 25) * nbMessages
    for(thread <- 1 to threads) {
      val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -c $thread -t $nbSec").lines
      val ppid = buffer(0).trim.toInt
      val monitoring = powerapi.start(1.seconds, PIDS(ppid)).attachReporter(classOf[PowerspyReporter])
      Seq("kill", "-SIGCONT", ppid+"").!
      
      // Get the worker pid corresponding to the stress.
      while(outputs.size < 1) {
        Seq("pgrep", "stress").!(logger)
      }

      val lastWorkerPid = outputs.last.trim.toInt
      outputs.clear
      var cpulimitPid = -1

      // Core load.
      for(i <- 100 to 25 by -25) {
        Seq("cpulimit", "-l", i+"", "-p", lastWorkerPid.toString).run(logger)
        if(cpulimitPid != -1) Seq("kill", "-9", cpulimitPid.toString).!(logger)
        outputs.clear
        
        while(outputs.size < 1) {
          Seq("pgrep", "cpulimit").!(logger)
        }
        cpulimitPid = outputs.last.trim.toInt
        outputs.clear
        
        Thread.sleep((nbMessages.seconds).toMillis)
        (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
        Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
      }

      monitoring.waitFor(1.milliseconds)

      // To be sure, we kill all the processes.
      Seq("killall", "cpulimit").!(logger)
      Seq("kill", "-9", ppid+"").!(logger)
      outputs.clear
    }

    // To be sure, we kill all the processes.
    Seq("killall", "cpulimit", "stress").!(logger)
    outputs.clear*/

    // Move files to the right place, to save them for the future regression.
    s"$samplingPath/$index/$frequency/cpu".createDirectory(failIfExists=false)
    (Path(".") * "*.dat").foreach(path => {
      val name = path.name
      val target: Path = s"$samplingPath/$index/$frequency/cpu/$name"
      path.moveTo(target=target, replace=true)
    })

    // We stress only one core (we consider that the environment is heterogeneous).
    /*for(kbytes <- Iterator.iterate(1)(_ * base).takeWhile(_ < l3Cache)) {
      val bytes = kbytes * 1024
      // Launch stress command to stimulate the available cache on the processor.
      val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -m 1 --vm-bytes $bytes -t $nbMessages").lines
      val ppid = buffer(0).trim.toInt
      // Pin the process on the first core (physical or logical).
      Seq("taskset", "-cp", "0", ppid+"").run(logger)
      outputs.clear

      val monitoring = powerapi.start(1.seconds, PIDS(ppid)).attachReporter(classOf[PowerspyReporter])
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
      Seq("taskset", "-cp", "0", ppid+"").run(logger)
      outputs.clear

      val monitoring = powerapi.start(1.seconds, PIDS(ppid)).attachReporter(classOf[PowerspyReporter])
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
    })*/

    powerapi.system.stop(libpfmListener)
  }

  def run() = {
    LibpfmUtil.initialize()

    val availableFreqs = scala.collection.mutable.SortedSet[Long]()
    if(cpuFreq) {
      // Get the available frequencies from sys virtual filesystem.
      (for(thread <- 0 until threads) yield (scalingFreqPath.replace("%?", thread.toString))).foreach(filepath => {
        availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
      })

      val turbo = availableFreqs.max
      availableFreqs.clear
      availableFreqs += turbo

      println(availableFreqs)
      
      // Set the default governor with the userspace governor. It allows us to control the frequency.
      Seq("bash", "-c", "echo userspace | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
    }

    // Cleaning phase
    Path.fromString(samplingPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    powerapi = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorTimestamp
    events.distinct.foreach(event => powerapi.configure(new SensorLibpfmConfigured(event)))

    for(index <- 1 to samples) {
      if(cpuFreq) {
        for(frequency <- availableFreqs) {
          // Set the frequency
          Seq("bash", "-c", s"echo $frequency | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_setspeed > /dev/null").!
          
          sampling(index, frequency)
        }
      }

      else sampling(index, defaultFrequency)
    }

    shutdownThread.start()
    shutdownThread.join()
    shutdownThread.remove()
  }
}
