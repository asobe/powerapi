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

import scalax.file.Path
import scalax.file.ImplicitConversions.string2path
import scalax.io.Resource

/**
 * Allows to process the data collected and create the csv files used during the regression step.
 */
/*object Processing extends Configuration {
  private def process(frequency: Long, turboMode: Boolean) = {
    implicit val codec = scalax.io.Codec.UTF8

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

    // Used to build the csv arrays.
    val csvData = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()
    val csvPowers = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()
    val csvIdlePower = scala.collection.mutable.LinkedHashMap[Int, scala.collection.mutable.ArrayBuffer[String]]()

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
      val nbLinesIdlePowerCSV = csvIdlePower.keys.size

      // PART 1: Compute the medians for each event.
      for(event <- events.distinct.sorted) {
        // Organize the data.
        val eventData = scala.collection.mutable.HashMap[Int, scala.collection.mutable.ArrayBuffer[Double]]()
        
        eventsPaths.filter(_.path.contains(s"$event")).toArray.sortWith((path1, path2) => sortPaths(path1, path2)).foreach(path => {
          var index = 0
          
          while(!data(path).isEmpty) {
            val existing = eventData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
            val buffer = data(path).takeWhile(_.contains(separatorSymbol) == false).filter(line => line != "" && line != "0").map(_.toDouble)

            // tail is used to remove the separator.
            var tmpData = data(path).dropWhile(_.contains(separatorSymbol) == false)

            if(!tmpData.isEmpty) {
              data(path) = tmpData.tail
            }

            else data(path) = tmpData

            // Used to flush the buffer (remove the useless values).
            if(buffer.size > nbMessages - 10) { 
              existing ++= buffer
              eventData(index) = existing
              index += 1
            }
          }
        })
        
        // Compute the medians and store values inside the corresponding csv buffer.
        for(i <- 0 until eventData.keys.size) {
          val medianVal = if(!eventData(i).isEmpty) Util.median(eventData(i)) else 0l
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
        // Special case for the file wich contains the idle power. We keep the values to compute the idle power.
        if(path.path.endsWith(s"$eltIdlePower/$outPathPowerspy")) {
          val existing = powersData.getOrElse(0, scala.collection.mutable.ArrayBuffer[Double]())
          existing ++= powers(path).takeWhile(_.contains(separatorSymbol) == false).filter(line => line != "" && line != "0").map(_.toDouble)
          idlePowersData(0) = existing
          
          // tail is used to remove the separator.
          var tmpData = powers(path).dropWhile(_.contains(separatorSymbol) == false)

          if(!tmpData.isEmpty) {
            powers(path) = tmpData.tail
          }

          else powers(path) = tmpData
        }

        // Idle power consumption
        val idlePowerMedian = if(!idlePowersData(0).isEmpty) Util.median(idlePowersData(0)) else 0d
        val idlePowerLineCSV = csvIdlePower.getOrElse(nbLinesIdlePowerCSV, scala.collection.mutable.ArrayBuffer[String]())
        idlePowerLineCSV += idlePowerMedian.toString
        csvIdlePower(nbLinesIdlePowerCSV) = idlePowerLineCSV
 
        var index = 0
        var step = 0
        var enabledCores = 1

        while(!powers(path).isEmpty) {
          val existing = powersData.getOrElse(index, scala.collection.mutable.ArrayBuffer[Double]())
          var powersTmp = powers(path).takeWhile(_.contains(separatorSymbol) == false).filter(line => line != "" && line != "0").map(_.toDouble)
          
          // Special case for the turbo mode
          if(turboMode) {
            if(enabledCores == (Util.topology.size + 1)) {
              enabledCores = 1
            }
            
            powersTmp = powersTmp.map(p => {
              ((p - idlePowerMedian) / enabledCores.toDouble) + idlePowerMedian
            })

            step += 1
            
            if(step == nbSteps) {
              enabledCores += 1
              step = 0
            }
          }
          
          existing ++= powersTmp
          powersData(index) = existing
         
          // tail is used to remove the separator.
          val tmpData = powers(path).dropWhile(_.contains(separatorSymbol) == false)

          if(!tmpData.isEmpty) {
            powers(path) = tmpData.tail
          }

          else powers(path) = tmpData

          index += 1
        }
      })

      // Workload power consumption
      for(i <- 0 until powersData.keys.size) {
        val medianVal = if(!powersData(i).isEmpty) Util.median(powersData(i)) else 0d
        val line = csvPowers.getOrElse(nbLinesPowersCSV + i, scala.collection.mutable.ArrayBuffer[String]())
        line += medianVal.toString
        csvPowers(nbLinesPowersCSV + i) = line
      }
      // END PART 2
    }

    // Write the corresponding csv files in a dedicated directory.
    s"$processingPath/$frequency".createDirectory(failIfExists=false)
    
    val idlePower = csvIdlePower.values.toArray
    val powers = csvPowers.values.toArray
    val data = csvData.values.toArray

    
    idlePower.foreach(values => Resource.fromFile(s"$processingPath/$frequency/idle-power.csv").append(values.mkString(csvDelimiter) + scalax.io.Line.Terminators.NewLine.sep))
  
    for(i <- 0 until data.size) {
      if(data(i).size == events.size) {
        Resource.fromFile(s"$processingPath/$frequency/counters.csv").append(data(i).mkString(csvDelimiter) + scalax.io.Line.Terminators.NewLine.sep)
        Resource.fromFile(s"$processingPath/$frequency/powers.csv").append(powers(i).mkString(csvDelimiter) + scalax.io.Line.Terminators.NewLine.sep)
      }
    }
  }

  def run() = {
    // Cleaning phase
    Path.fromString(processingPath).deleteRecursively(force = true)
    if(!Path.fromString(samplingPath).exists) System.exit(0)

    var frequencies = Util.availableFrequencies.getOrElse(Array())
    var turboFreq = -1l

    if(turbo) {
      turboFreq = frequencies.last
      frequencies = frequencies.slice(0, (frequencies.size - 1))
    }
    
    if(cpuFreq) {      
      for(frequency <- frequencies) {
        process(frequency, false)
      }
      
      if(turbo) {
        process(turboFreq, true)
      }
    }

    else process(defaultFrequency, false)
  }
}*/
