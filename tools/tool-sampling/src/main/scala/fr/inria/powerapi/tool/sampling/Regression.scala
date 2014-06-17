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

import nak.regress.LinearRegression
import breeze.linalg._
import scalax.file.Path
import scalax.io.Resource

/**
 * Allows to compute the formulae related to the frequency, hardware counters and powers. They are written into a unique file.
 */
object MultipleLinearRegression extends Configuration {
  private def compute(frequency: Long) = {
    // Formula part
    val counters = csvread(file = new java.io.File(s"$processingPath/$frequency/counters.csv"), 
      separator = csvDelimiter.charAt(0),
      skipLines = 1)
    val ones = DenseMatrix.ones[Double](counters.rows, 1)
    val data = DenseMatrix.horzcat(counters, ones)
    val powers = csvread(file = new java.io.File(s"$processingPath/$frequency/powers.csv"),
      separator = csvDelimiter.charAt(0),
      skipLines = 1)
    LinearRegression.regress(data, powers(::, 0)).toArray
  }

  def run() = {
    implicit val codec = scalax.io.Codec.UTF8

    // Cleaning phase
    Path.fromString(formulaePath).deleteRecursively(force = true)

    if(!Path.fromString(processingPath).exists) System.exit(0)
    
    val lines = scala.collection.mutable.ArrayBuffer[String]()
    lines += "powerapi.libpfm.formulae = [" + scalax.io.Line.Terminators.NewLine.sep
    val idlePowers = scala.collection.mutable.ArrayBuffer[Double]()
    
    if(cpuFreq) {
      val availableFreqs = scala.collection.mutable.SortedSet[Long]()
      
      // Get the available frequencies from sys virtual filesystem.
      (for(thread <- 0 until threads) yield (scalingFreqPath.replace("%?", thread.toString))).foreach(filepath => {
        availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
      })

      for(frequency <- availableFreqs) {
        val coefficients = compute(frequency) 
        lines += "\t{freq = " + frequency.toString + ", formula = [" + coefficients.mkString(",") + "]}" + scalax.io.Line.Terminators.NewLine.sep
        
        // Contant part.
        val idlePower = coefficients(coefficients.size - 1)
        idlePowers += idlePower.toDouble
      }
    }

    else {
      val coefficients = compute(defaultFrequency)
      lines += "\t{freq = " + defaultFrequency.toString + ", formula = [" + coefficients.mkString(",") + "]}" + scalax.io.Line.Terminators.NewLine.sep
      // Contant part.
      val idlePower = coefficients(coefficients.size - 1)
      idlePowers += idlePower.toDouble
    }

    lines += "]" + scalax.io.Line.Terminators.NewLine.sep + "" + scalax.io.Line.Terminators.NewLine.sep
    lines += "powerapi.libpfm.idle-power = " + Util.median(idlePowers)

    lines.foreach(line => Resource.fromFile(s"$formulaePath/libpfm-formula.conf").append(line))
  }
}