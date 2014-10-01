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
import fr.inria.powerapi.sensor.libpfm.{ LibpfmUtil, SensorLibpfmCore }
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp

//import java.util.{ Timer, TimerTask }
import java.util.concurrent.TimeUnit._
import java.util.concurrent.{ Executors, ScheduledExecutorService }

import akka.actor.{ ActorRef, Props }

import scala.concurrent.duration.{ Duration, DurationInt }
import scala.sys.process._

import scalax.file.Path
import scalax.file.ImplicitConversions.string2path

/** 
 * Allows to run the sampling step (collect the data related to several stress) by following the PPID and use the inherit option.
 * Be careful, we need the root access to write in sys virtual filesystem, else, we can not control the frequency.
 */
object Sampling extends Configuration {
  val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt
  val trashLogger = ProcessLogger(out => {}, err => {})
 
  val pathMatcher = s"$outBasePathLibpfm*.dat" 

  var powerapi: fr.inria.powerapi.library.PAPI = null
  // logical os index -> (governor, cur, min, max)
  val backup = scala.collection.mutable.HashMap[String, (String, Long, Long, Long)]()
  for(osIndex <- 0 until cores) {
    val governor = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$osIndex/cpufreq/scaling_governor").lines.toArray.apply(0)
    val cur = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$osIndex/cpufreq/scaling_setspeed").lines.toArray.apply(0)
    val min = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$osIndex/cpufreq/scaling_min_freq").lines.toArray.apply(0)
    val max = Seq("bash", "-c", s"cat /sys/devices/system/cpu/cpu$osIndex/cpufreq/scaling_max_freq").lines.toArray.apply(0)
    backup += (osIndex.toString -> (governor, fr.inria.powerapi.library.Util.stringToLong(cur).getOrElse(-1l), fr.inria.powerapi.library.Util.stringToLong(min).getOrElse(-1l), fr.inria.powerapi.library.Util.stringToLong(max).getOrElse(-1l)))
  }

  val shutdownThread = scala.sys.ShutdownHookThread {
    println("\nPowerAPI is going to shutdown ...")
    
    if(powerapi != null) {
      powerapi.stop
    }

    LibpfmUtil.terminate()

    backup.foreach {
      case(thread, (governor, cur, min, max)) => {
        Seq("bash", "-c", s"echo $governor > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_governor").!

        if(min != -1) { 
          Seq("bash", "-c", s"echo $min > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_min_freq").!(trashLogger)
        }

        if(max != -1) { 
          Seq("bash", "-c", s"echo $max > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_max_freq").!(trashLogger)
        }

        if(cur != -1) { 
          Seq("bash", "-c", s"echo $cur > /sys/devices/system/cpu/cpu$thread/cpufreq/scaling_setspeed").!(trashLogger)
        }
      }
    }
  }

  // Method used to start a stress with the start.bash script (used to synchronize all the stuffs but it's not mandatory).
  // All the stress are pinned to one HT/Core.
  private def startStress(osIndexes: Array[Int], duration: Option[Int]): Array[Int] = {
    val ppids = scala.collection.mutable.ArrayBuffer[Int]()

    osIndexes.foreach(osIndex => {
      val buffer = duration match {
        case Some(t) => Seq("bash", "./src/main/resources/start.bash", s"stress -c 1 -t $t").lines
        case None => Seq("bash", "./src/main/resources/start.bash", s"stress -c 1").lines
      }
      val ppid = buffer(0).trim.toInt
      ppids += ppid
      Seq("taskset", "-cp", osIndex.toString, ppid.toString).!(trashLogger)
    })

    ppids.toArray
  }

  // Wake up the stress represented by their PIDS.
  private def wakeUpStress(ppids: Array[Int]): Array[Int] = {
    var outputs = scala.collection.mutable.ArrayBuffer[String]()
    // We assume the sampling is isolated.
    val existingStress = Seq("pgrep", "stress").lines_!.toArray

    (Seq("kill", "-SIGCONT") ++ ppids.map(_.toString)).!(trashLogger)

    // Check if all the stress are launched.
    // Only stress -c 1 are used during the sampling.
    while(outputs.size < (existingStress.size + (2 * ppids.size))) {
      outputs = Seq("pgrep", "stress").lines_!.to[scala.collection.mutable.ArrayBuffer]
    }

    (outputs.map(_.trim.toInt) -- existingStress.map(_.trim.toInt) -- ppids).toArray
  }

  // Decreasing load
  private def decreasingLoad(osIndexes: Array[Int], scheduler: ScheduledExecutorService, stressDuration: Int, stepDuration: Int, isDelimited: Boolean) = {
    val PSFormat = """root\s+([\d]+)\s.*""".r 

    val ppids = startStress(osIndexes, Some(stressDuration))
    val lastWorkerPids = wakeUpStress(ppids)
    var lastCpuLimitPids = Array[String]()

    var load = 75
    val loadStep = 25

    scheduler.scheduleAtFixedRate(new Runnable() {
      def run() {
        try { 
          lastWorkerPids.foreach(pid => Seq("cpulimit", "-l", load.toString, "-p", pid.toString).run(trashLogger))
          lastCpuLimitPids.foreach(pid => Seq("kill", "-9", pid).!(trashLogger))
          
          val cmd = Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "egrep -i '" + lastWorkerPids.map("cpulimit.*-p " + _.toString + ".*").mkString("|") + "'") #> Seq("bash", "-c", "grep -v egrep")                    
          var outputs = scala.collection.mutable.ArrayBuffer[String]()
          var nbMs = 0

          while(outputs.size < lastWorkerPids.size && nbMs < 500) {
            outputs = cmd.lines_!.to[scala.collection.mutable.ArrayBuffer]
            Thread.sleep((100.milliseconds).toMillis)
            nbMs += 100
          }
 
          lastCpuLimitPids = (for(line <- outputs) yield {
            line match {
              case PSFormat(pid) => pid.toInt
              case _ => -1
            }
          }).toArray.map(_.toString)

          outputs.clear

          if(isDelimited) {
            events.distinct.par.foreach(event => {
              powerapi.system.eventStream.publish(LineToWrite(s"$outBasePathLibpfm$event.dat", s"$separator\n"))
            })
            powerapi.system.eventStream.publish(LineToWrite(s"$outPathPowerspy", s"$separator\n"))
          }
          
          load -= loadStep
          
          if(load == 0) load = 100
        }
        catch {
          case e: Exception => println(e.printStackTrace); scheduler.shutdown
        }
      }
    }, (stepDuration.seconds).toMillis, (stepDuration.seconds).toMillis, MILLISECONDS)
  }

  private def sampling(index: Int, frequency: Long, turboMode: Boolean) = {
    val firstCore = topology.head
    val remainingCores = topology.tail
    /**
     * Create the HTs combinations (if there is not HT mode, the entire core will be stressed).
     * For example, if the core0 is divided in HT0 and HT1, the combinations will be [0;1;0,1].
     */
    val firstCoreC = (1 to firstCore._2.size).flatMap(firstCore._2.combinations)

    // Init PAPI.
    powerapi = new PAPI with SensorPowerspy with SensorLibpfmCore with FormulaPowerspy with AggregatorTimestamp
    //Writer
    val writer = powerapi.system.actorOf(Props[Writer])
    // Idle power
    var libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])
    powerapi.start(1.seconds, PIDS(currentPid)).attachReporter(classOf[PowerspyReporter]).waitFor(nbMessages.seconds + 20.seconds)
    powerapi.system.stop(libpfmListener)
    
    events.distinct.par.foreach(event => {
      powerapi.system.eventStream.publish(LineToWrite(s"$outBasePathLibpfm$event.dat", s"$separator\n"))
    })
    powerapi.system.eventStream.publish(LineToWrite(s"$outPathPowerspy", s"$separator\n"))
    
    /** 
     * This loop was built to stress the first core (with/without HTs). Only one loop is executed if there is not a turbo mode, else the other cores are stressed
     * to catch all the steps in the turbo mode. The idea is to get data on the different steps for the first core combinations.
     */   
    for(i <- 0 until topology.size if i == 0 || turboMode) {
      val osIndexesToStressTB = remainingCores.slice(0, i).values.flatten.toArray
 
      if(osIndexesToStressTB.size > 0) {
        val stressPidsCoresTB = startStress(osIndexesToStressTB, None)
        (Seq("kill", "-SIGCONT") ++ stressPidsCoresTB.map(_.toString)).!(trashLogger)
      }
      
      for(j <- 0 until firstCoreC.size) {
        var stressDuration = nbMessages
        var stepDuration = nbMessages
        var isDelimited = true
        libpfmListener = powerapi.system.actorOf(Props[LibpfmListener])
        val monitoring = powerapi.start(1.seconds, PIDS(currentPid)).attachReporter(classOf[PowerspyReporter])
        val schedulers = scala.collection.mutable.ArrayBuffer[ScheduledExecutorService]()
        val currentCombiFirstC = firstCoreC(j)

        for(k <- 0 until currentCombiFirstC.size) {
          val scheduler = Executors.newScheduledThreadPool(1)
          val osIndexes = scala.collection.mutable.ArrayBuffer[Int](currentCombiFirstC(k))
          stressDuration = nbMessages * (math.pow(nbSteps, currentCombiFirstC.size)).toInt
          decreasingLoad(osIndexes.toArray, scheduler, stressDuration, stepDuration, isDelimited)
          stepDuration *= nbSteps
          schedulers += scheduler
          isDelimited = false
        }

        Thread.sleep(stressDuration.seconds.toMillis)
        schedulers.foreach(scheduler => scheduler.shutdown)
        monitoring.waitFor(1.milliseconds)
        powerapi.system.stop(libpfmListener)
      }

      Seq("bash", "-c", "killall cpulimit stress").!(trashLogger)
    }

    powerapi.system.stop(writer)

    // Move files to the right place, to save them for the future regression.
    s"$samplingPath/$index/$frequency".createDirectory(failIfExists=false)
    (Path(".") * "*.dat").foreach(path => {
      val name = path.name
      val target: Path = s"$samplingPath/$index/$frequency/$name"
      path.moveTo(target=target, replace=true)
    })
  }

  def run() = {
    // Cleaning phase
    Path.fromString(samplingPath).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    LibpfmUtil.initialize()
    
    var frequencies = Util.availableFrequencies.getOrElse(Array[Long](defaultFrequency))
    val turboFreq = frequencies.last
    
    frequencies = {
      if(turbo) {
        frequencies.slice(0, (frequencies.size - 1))
      }

      else frequencies.slice(0, frequencies.size)
    }
   
    for(index <- 1 to samples) {
      if(!dvfs) {
        sampling(index, defaultFrequency, false)
      }

      else {
        // Intel processor are homogeneous, we cannot control the frequencies per core. 
        // Set the default governor with the userspace governor. It allows us to control the frequency.
        Seq("bash", "-c", "echo userspace | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!
        
        for(frequency <- frequencies) {
          // Set the frequency
          Seq("bash", "-c", s"echo $frequency | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_setspeed > /dev/null").!
      
          sampling(index, frequency, false)
          
          powerapi.stop
          powerapi = null
          Thread.sleep(5000)
        }

        if(turbo) {
          val minFreq = frequencies.head

          // Special case for the turbo mode, we can't control the frequency to be able to capture the different heuristics.
          Seq("bash", "-c", s"echo $minFreq | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq > /dev/null").!
          Seq("bash", "-c", s"echo $turboFreq | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq > /dev/null").!
          Seq("bash", "-c", "echo ondemand | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null").!

          sampling(index, turboFreq, true)

          powerapi.stop
          powerapi = null
        }
      }
    }

    shutdownThread.start()
    shutdownThread.join()
    shutdownThread.remove()
  }
}
