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
import fr.inria.powerapi.sensor.libpfm.{ LibpfmUtil, SensorLibpfmCoreConfigured }
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
  implicit val codec = scalax.io.Codec.UTF8
  val outputs = scala.collection.mutable.ArrayBuffer[String]()
  val outputLogger = ProcessLogger(out => outputs += out, err => {})
  val trashLogger = ProcessLogger(out => {}, err => {})
  val pathMatcher = s"$outBasePathLibpfm*.dat" 

  var powerapi: fr.inria.powerapi.library.PAPI = null
  // thread -> (governor, min, max)
  val backup = scala.collection.mutable.HashMap[String, (String, Long, Long)]()
  for(thread <- 0 until threads) {
    val governor = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_governor").lines.toArray.apply(0)
    val min = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_min_freq").lines.toArray.apply(0)
    val max = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_max_freq").lines.toArray.apply(0)
    backup += (thread.toString -> (governor, fr.inria.powerapi.library.Util.stringToLong(min).getOrElse(-1l), fr.inria.powerapi.library.Util.stringToLong(max).getOrElse(-1l)))
  }

  val shutdownThread = scala.sys.ShutdownHookThread {
    println("\nPowerAPI is going to shutdown ...")
    
    if(powerapi != null) {
      powerapi.stop
    }

    LibpfmUtil.terminate()

    backup.foreach {
      case(thread, (governor, min, max)) => {
        if(min != -1) { 
          Seq("bash", "-c", s"echo $min > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_min_freq").!
        }

        if(max != -1) { 
          Seq("bash", "-c", s"echo $max > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_max_freq").!
        }
        
        Seq("bash", "-c", s"echo $governor > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_governor").!
      }
    }
  }

  private def decreasingLoad(osIndexes: Array[Int]) = {
    val nbSec = nbSteps * nbMessages 
    // We assume that the sampling is isolated so we are able to get all the stress launched during it.
    Seq("pgrep", "stress").!(outputLogger) 
    val existingStress = outputs.clone
    outputs.clear
    
    val stressPPIDS = scala.collection.mutable.ArrayBuffer[String]()
    for(osIndex <- osIndexes) {
      val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -c 1 -t $nbSec").lines
      val ppid = buffer(0).trim
      Seq("taskset", "-cp", osIndex.toString, ppid).!(trashLogger)
      stressPPIDS += ppid
    }

    (Seq("kill", "-SIGCONT") ++ stressPPIDS).!

    // stress -c 1 => 2 processes
    while(outputs.size < (existingStress.size + (2 * osIndexes.size))) {
      Seq("pgrep", "stress").!(outputLogger)
    }
    
    // .last because there is only one element in this array.
    val lastWorkerPids = (outputs.map(_.trim) -- existingStress.map(_.trim) -- stressPPIDS)
    outputs.clear
    
    var lastCpuLimitPids = scala.collection.mutable.ArrayBuffer[String]()

    // Decreasing load.
    for(i <- 100 to 25 by -25) {
      lastWorkerPids.foreach(pid => Seq("cpulimit", "-l", i+"", "-p", pid).run(trashLogger))
      lastCpuLimitPids.foreach(pid => Seq("kill", "-9", pid).!(trashLogger))

      while(outputs.size < lastWorkerPids.size) {
        Seq("pgrep", "cpulimit").!(outputLogger)
      }
      
      lastCpuLimitPids = outputs.map(_.trim)
      outputs.clear

      Thread.sleep((nbMessages.seconds).toMillis)
      (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
      Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
    }
  }

  private def sampling(index: Int, frequency: Long, turboMode: Boolean) = {
    val cores = Util.topology
    val firstCore = cores.head 

    // Init PAPI.
    powerapi = new PAPI with SensorPowerspy with FormulaPowerspy with AggregatorTimestamp
    events.distinct.foreach(event => powerapi.configure(new SensorLibpfmCoreConfigured(event, bitset, firstCore._1, firstCore._2)))
    
    // Get idle power.
    powerapi.start(1.seconds, PIDS(currentPid)).attachReporter(classOf[PowerspyReporter]).waitFor(nbMessages.seconds + 10.seconds)
    Resource.fromFile(outPathPowerspy).append(separator + scalax.io.Line.Terminators.NewLine.sep)
    (Path(".") * pathMatcher).foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))
    
    // Previous HTS to stress (not used if the HT is not enabled).
    var previous = scala.collection.mutable.ArrayBuffer[Int]()

    // Decreasing load.
    for(i <- 0 until firstCore._2.size) { 
      val libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])
      val monitoring = powerapi.start(1.seconds, PIDS(currentPid)).attachReporter(classOf[PowerspyReporter])
      
      if(turboMode) {
        val indexes = for(osIndexes <- cores.values.toArray) yield(osIndexes(i))

        for(j <- 1 to indexes.size) {
          // Full load on the previous HTS
          for(prOsIndex <- previous.slice(0, j)) {
            val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -c 1").lines
            val ppid = buffer(0).trim.toInt
            Seq("taskset", "-cp", prOsIndex.toString, ppid.toString).!(trashLogger)
            outputs.clear
            Seq("kill", "-SIGCONT", ppid.toString).!            
          }
       
          decreasingLoad(indexes.slice(0, j))
        }
        
        previous.clear
        previous ++= indexes
      }

      else {
        // Full load on the previous HTS
        for(prOsIndex <- previous) {
          val buffer = Seq("bash", "./src/main/resources/start.bash", s"stress -c 1").lines
          val ppid = buffer(0).trim.toInt
          Seq("taskset", "-cp", prOsIndex.toString, ppid.toString).!(trashLogger)
          outputs.clear
          Seq("kill", "-SIGCONT", ppid.toString).!
        }

        val osIndex = firstCore._2.apply(i)
        decreasingLoad(Array(osIndex))
        previous += osIndex
      }

      monitoring.waitFor(1.milliseconds)
      powerapi.system.stop(libpfmListener)

      // To be sure, we kill all the processes.
      Seq("killall", "cpulimit", "stress").!(trashLogger)
      outputs.clear
      Thread.sleep(5000)
    }

    powerapi.stop
    Thread.sleep(5000)
    powerapi = null

    // Move files to the right place, to save them for the future regression.
    s"$samplingPath/$index/$frequency/cpu".createDirectory(failIfExists=false)
    (Path(".") * "*.dat").foreach(path => {
      val name = path.name
      val target: Path = s"$samplingPath/$index/$frequency/cpu/$name"
      path.moveTo(target=target, replace=true)
    })
  }

  def run() = {
    // Cleaning phase
    Path.fromString(samplingPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    LibpfmUtil.initialize()
    
    var frequencies = Util.availableFrequencies.getOrElse(Array())
    var minFreq = -1l
    var turboFreq = -1l
   
    if(turbo) {
      minFreq = frequencies.head
      turboFreq = frequencies.last
      frequencies = frequencies.slice(0, (frequencies.size - 1))
    } 
    
    for(index <- 1 to samples) {
      if(!cpuFreq) {
        sampling(index, defaultFrequency, false)
      }

      else {
        // Intel processor are homogeneous, we cannot control the frequencies per core. 
        // Set the default governor with the userspace governor. It allows us to control the frequency.
        Seq("bash", "-c", "echo userspace | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
        
        for(frequency <- frequencies) {
          // Set the frequency
          Seq("bash", "-c", s"echo $frequency | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_setspeed > /dev/null").!
          //Seq("bash", "-c", s"echo $frequency | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq > /dev/null").!
      
          sampling(index, frequency, false)
          Thread.sleep(2000)
        }
        
        if(turbo) {
          // Special case for the turbo mode, we can't control the frequency to be able to capture the different heuristics.
          Seq("bash", "-c", s"echo $minFreq | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq > /dev/null").!
          Seq("bash", "-c", s"echo $turboFreq | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq > /dev/null").!
          Seq("bash", "-c", "echo ondemand | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
          
          sampling(index, turboFreq, true)
        }
      }
    }

    shutdownThread.start()
    shutdownThread.join()
    shutdownThread.remove()
  }
}
