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

import scala.concurrent.duration.DurationInt
import scala.sys.process._
import scalax.file.Path
import scalax.file.ImplicitConversions.string2path
import scalax.io.Resource

object Experiments extends SpecConfiguration with ParsecConfiguration {
  val currentPid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt  
  
  def specCPUAllCores = {
    implicit val codec = scalax.io.Codec.UTF8
    val separator = "="
    val dataP = "speccpu-core"
    
    // Cleaning phase
    Path.fromString(dataP).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    val benchmarks = scala.collection.mutable.ArrayBuffer[String]()
    // Float benchmarks
    benchmarks ++= Array("410.bwaves", "416.gamess", "433.milc", "434.zeusmp", "435.gromacs", "436.cactusADM", "437.leslie3d", "450.soplex", "453.povray", "454.calculix", "459.GemsFDTD", "465.tonto", "470.lbm")
    // Int benchmarks
    benchmarks ++= Array("481.wrf", "482.sphinx3", "400.perlbench", "401.bzip2", "403.gcc", "429.mcf", "445.gobmk", "456.hmmer", "458.sjeng", "462.libquantum", "464.h264ref", "471.omnetpp", "473.astar", "483.xalancbmk")
    
    // Kill all the running benchmarks (if there is still alive from another execution).
    val benchsToKill = (Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "grep _base.amd64-m64-gcc43-nn") #> Seq("bash", "-c", "head -n 1") #> Seq("bash", "-c", "cut -d '/' -f 6") #> Seq("bash", "-c", "cut -d ' ' -f1")).lines.toArray
    benchsToKill.foreach(benchmark => Seq("bash", "-c", s"killall -s KILL specperl runspec specinvoke $benchmark &> /dev/null").run)
    
    // To be sure that the benchmarks are compiled, we launch the compilation before all the monitorings (no noise).
    /*benchmarks.foreach(benchmark => {
      val res = Seq("bash", "./src/main/resources/compile_spec.bash", specpath, benchmark).!
      if(res != 0) throw new RuntimeException("Umh, there is a problem with the compilation, maybe dependencies are missing.")
    })*/
   
    // One libpfm sensor per event.
    Monitor.libpfm = new fr.inria.powerapi.library.PAPI with fr.inria.powerapi.sensor.libpfm.SensorLibpfmCore with fr.inria.powerapi.sensor.powerspy.SensorPowerspy with fr.inria.powerapi.formula.powerspy.FormulaPowerspy with fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp {
      override lazy val bits = scala.collection.immutable.HashMap[Int, Int](0 -> 1)
    }

    for(benchmark <- benchmarks) {
      // Cleaning phase
      (Path(".") * "*.dat").foreach(path => path.delete(force = true))
      // Kill all the running benchmarks (if there is still alive from another execution).
      val benchsToKill = (Seq("bash", "-c", "ps -ef") #> Seq("bash", "-c", "grep _base.amd64-m64-gcc43-nn") #> Seq("bash", "-c", "head -n 1") #> Seq("bash", "-c", "cut -d '/' -f 6") #> Seq("bash", "-c", "cut -d ' ' -f1")).lines.toArray
      benchsToKill.foreach(benchmark => Seq("bash", "-c", s"killall -s KILL specperl runspec specinvoke $benchmark &> /dev/null").run)

      // Start a monitoring to get the idle power.
      // We add some time because of the sync. between PowerAPI & PowerSPY.
      Monitor.libpfm.start(1.seconds, fr.inria.powerapi.library.PIDS(currentPid)).attachReporter(classOf[Reporter]).waitFor(20.seconds)
      Resource.fromFile("output-powerspy.dat").append(separator + scalax.io.Line.Terminators.NewLine.sep)

      // Start the libpfm sensor message listener to intercept the LibpfmSensorMessage.
      val libpfmListener = Monitor.libpfm.system.actorOf(akka.actor.Props[LibpfmListener])
      val libpfmWriter = Monitor.libpfm.system.actorOf(akka.actor.Props[Writer])

      // Here, we used a specific bash script to be sure that the command in not launch before to open and reset the counters.
      val buffer = Seq("bash", "./src/main/resources/start.bash", s"./src/main/resources/start_spec.bash $specpath $benchmark").lines
      val ppid = buffer(0).trim.toInt

      val monitoring = Monitor.libpfm.start(1.seconds, fr.inria.powerapi.library.PIDS(ppid)).attachReporter(classOf[Reporter])
      Seq("kill", "-SIGCONT", ppid+"").!

      while(Seq("kill", "-0", ppid+"").! == 0) {
        Thread.sleep((15.seconds).toMillis)
      }

      monitoring.waitFor(1.milliseconds)

      Monitor.libpfm.system.stop(libpfmListener)
      Monitor.libpfm.system.stop(libpfmWriter)

      // Move files to the right place, to save them for the future regression.
      s"$dataP/$benchmark".createDirectory(failIfExists=false)
      (Path(".") * "*.dat").foreach(path => {
        val name = path.name
        val target: Path = s"$dataP/$benchmark/$name"
        path.moveTo(target=target, replace=true)
      })
    }

    Monitor.libpfm.stop
    Monitor.libpfm = null
  }

  def parsecAllCores = {
    implicit val codec = scalax.io.Codec.UTF8
    val separator = "="
    val PSFormat = """\s*([\d]+)\s.*""".r 
    val dataP = "parsec-core"
    
    // Cleaning phase
    Path.fromString(dataP).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))
    
    val benchmarks = Array("blackscholes","bodytrack","facesim","fluidanimate","freqmine","swaptions","vips","x264")
    
    // One libpfm sensor per event.
    Monitor.libpfm = new fr.inria.powerapi.library.PAPI with fr.inria.powerapi.sensor.libpfm.SensorLibpfmCore with fr.inria.powerapi.sensor.powerspy.SensorPowerspy with fr.inria.powerapi.formula.powerspy.FormulaPowerspy with fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp {
      override lazy val bits = scala.collection.immutable.HashMap[Int, Int](0 -> 1)
    }

    for(benchmark <- benchmarks) {
      (Path(".") * "*.dat").foreach(path => path.delete(force = true))
      
      // Cleaning all the old runs.
      val oldPids = (Seq("bash", "-c", "ps -Ao pid,command") #> Seq("bash", "-c", "grep inst/amd64-linux.gcc/bin/") #> Seq("bash", "-c", "grep -v grep")).lines_!.toArray
      oldPids.foreach(oldPid => oldPid match {
        case PSFormat(pid) => Seq("kill", "-9", pid)
        case _ => None
      })

      // Start a monitoring to get the idle power.
      // We add some time because of the sync. between PowerAPI & PowerSPY.
      Monitor.libpfm.start(1.seconds, fr.inria.powerapi.library.PIDS(currentPid)).attachReporter(classOf[Reporter]).waitFor(20.seconds)
      Resource.fromFile("output-powerspy.dat").append(separator + scalax.io.Line.Terminators.NewLine.sep)

      // Start the libpfm sensor message listener to intercept the LibpfmSensorMessage.
      val libpfmListener = Monitor.libpfm.system.actorOf(akka.actor.Props[LibpfmListener])
      val libpfmWriter = Monitor.libpfm.system.actorOf(akka.actor.Props[Writer])

      val buffer = Seq("bash", "./src/main/resources/start.bash", s"./src/main/resources/start_parsec_core.bash $parsecpath $benchmark").lines
      val ppid = buffer(0).trim.toInt

      // Start a monitoring to get the values of the counters for the workload.
      val monitoring = Monitor.libpfm.start(1.seconds, fr.inria.powerapi.library.PIDS(ppid)).attachReporter(classOf[Reporter])
      Seq("kill", "-SIGCONT", ppid+"").!
      
      while(Seq("kill", "-0", ppid+"").! == 0) {
        Thread.sleep((15.seconds).toMillis)
      }
      
      monitoring.waitFor(1.milliseconds)
      
      Monitor.libpfm.system.stop(libpfmListener)
      Monitor.libpfm.system.stop(libpfmWriter)
      
      s"$dataP/$benchmark".createDirectory(failIfExists=false)
      (Path(".") * "*.dat").foreach(path => {
        val name = path.name
        val target: Path = s"$dataP/$benchmark/$name"
        path.moveTo(target=target, replace=true)
      })
    }

    Monitor.libpfm.stop
    Monitor.libpfm = null
  }

  def parsecProcess = {
    implicit val codec = scalax.io.Codec.UTF8
    val separator = "="
    val PSFormat = """\s*([\d]+)\s.*""".r
    val dataP = "parsec-process"

    // Cleaning phase
    Path.fromString(dataP).deleteRecursively(force = true)
    (Path(".") * "*.dat").foreach(path => path.delete(force = true))

    val benchmarks = Array("bodytrack","facesim","fluidanimate","freqmine","swaptions","vips","x264") 

    // One libpfm sensor per event.
    Monitor.libpfm = new fr.inria.powerapi.library.PAPI with fr.inria.powerapi.sensor.libpfm.SensorLibpfmCoreProcess with fr.inria.powerapi.sensor.powerspy.SensorPowerspy with fr.inria.powerapi.formula.libpfm.FormulaLibpfmCoreCycles with fr.inria.powerapi.formula.powerspy.FormulaPowerspy with AggregatorDevice {
      override lazy val threadsDepth = true
    }

    for(benchmark <- benchmarks) {
      val cmd = (Seq("bash", "-c", "ps -Ao pid,command") #> Seq("bash", "-c", s"grep /$benchmark/inst/amd64-linux.gcc/bin") #> Seq("bash", "-c", "grep -v grep"))
      (Path(".") * "*.dat").foreach(path => path.delete(force = true))

      // Cleaning all the old runs.
      val oldPids = (Seq("bash", "-c", "ps -Ao pid,command") #> Seq("bash", "-c", "grep inst/amd64-linux.gcc/bin/") #> Seq("bash", "-c", "grep -v grep")).lines_!.toArray
      oldPids.foreach(oldPid => oldPid match {
        case PSFormat(pid) => Seq("kill", "-9", pid)
        case _ => None
      })
      
      // Start a monitoring to get the idle power.
      // We add some time because of the sync. between PowerAPI & PowerSPY.
      Monitor.libpfm.start(1.seconds, fr.inria.powerapi.library.PIDS(currentPid)).attachReporter(classOf[Reporter]).waitFor(20.seconds)
      (Path(".") * "*.dat").foreach(path => path.append(separator + scalax.io.Line.Terminators.NewLine.sep))

      // Start the libpfm sensor message listener to intercept the LibpfmSensorMessage.
      val libpfmListener = Monitor.libpfm.system.actorOf(akka.actor.Props[LibpfmListener])
      val libpfmWriter = Monitor.libpfm.system.actorOf(akka.actor.Props[Writer])

      Seq("bash", "./src/main/resources/start_parsec_process.bash", parsecpath, benchmark).!
      var output = Array[String]()
      while(output.isEmpty) {
        output = cmd.lines_!.toArray
        Thread.sleep((1.seconds).toMillis)
      }
      
      var pid = 0
      output(0) match {
        case PSFormat(p) => pid = p.trim.toInt
        case _ => println("oops")
      }
      
      val monitoring = Monitor.libpfm.start(1.seconds, fr.inria.powerapi.library.PIDS(pid)).attachReporter(classOf[Reporter])        
          
      while(Seq("kill", "-0", pid.toString).! == 0) {
        Thread.sleep((15.seconds).toMillis)
      }

      monitoring.waitFor(1.milliseconds)
      Monitor.libpfm.system.stop(libpfmListener)
      Monitor.libpfm.system.stop(libpfmWriter)

      // Move files to the right place, to save them for the future regression.
      s"$dataP/$benchmark".createDirectory(failIfExists=false)
      (Path(".") * "*.dat").foreach(path => {
        val name = path.name
        val target: Path = s"$dataP/$benchmark/$name"
        path.moveTo(target=target, replace=true)
      })
    }

    Monitor.libpfm.stop
    Monitor.libpfm = null
  }
}

// Object launcher.
object Monitor extends App {
  lazy val ClasspathFormat  = """-classpath\s+(.+)""".r

  val params =
    (for (arg <- args) yield {
      arg match {
        case ClasspathFormat(classpath) => ("classpath" -> classpath) 
        case _ => ("none" -> "")
      }
    }).toMap

  val classpath = params.getOrElse("classpath", "") 

  if(classpath != "") {
    if(!fr.inria.powerapi.library.Util.addResourceToClasspath(classpath)) {
      println("There was a problem during the classpath loading ! The tool will be not configured correctly.")
    }
  }
  
  var libpfm: fr.inria.powerapi.library.PAPI = null

  val shutdownThread = scala.sys.ShutdownHookThread {
    println("\nPowerAPI is going to shutdown ...")
    
    if(libpfm != null) {
      libpfm.stop
      fr.inria.powerapi.sensor.libpfm.LibpfmUtil.terminate()
    }
  }

  fr.inria.powerapi.sensor.libpfm.LibpfmUtil.initialize()  
  
  //Experiments.specCPUAllCores
  //Thread.sleep((10.seconds).toMillis)
  Experiments.parsecAllCores
  //Thread.sleep((10.seconds).toMillis)
  //Experiments.parsecProcess

  Monitor.shutdownThread.start()
  Monitor.shutdownThread.join()
  Monitor.shutdownThread.remove()
  System.exit(0)
}
