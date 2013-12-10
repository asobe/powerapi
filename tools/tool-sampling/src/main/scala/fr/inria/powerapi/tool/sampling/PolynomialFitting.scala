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

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigException

import scalax.io.Resource
import scalax.file.Path
import scalax.file.PathMatcher

import breeze.linalg.{DenseMatrix, DenseVector, mean, stddev}
import breeze.numerics.polyval

/**
 * Util class for polynomial regression
 * @author mcolmant
 */
class PolynomialFitting(var threshold: Double) extends SamplingConfiguration {
  // threshold must be between -1 and 1, but we use the abs value
  if(threshold < 0) {
    // default value
    threshold = math.abs(threshold)
  }

  if(threshold > 1) {
      threshold = 1
  }

  /**
   * Compute the median
   */
  def median[T](s: Seq[T])(implicit n: Fractional[T]) = {
    import n._
    val (lower, upper) = s.sortWith(_<_).splitAt(s.size / 2)
    if (s.size % 2 == 0) (lower.last + upper.head) / fromInt(2) else upper.head
  }

  /**
   * Read data sampling file performed by PowerSpy monitoring
   */
  def readMatrixFromFile: DenseMatrix[Double] = {
    // +1 because of the 0.0 usage percentage
    val nbLines = nbStep + 1
    val samplingFiles = Path.fromString(samplesDirPath).descendants(PathMatcher.All, 1).toArray
    // Map to save all the results
    var acc = scala.collection.mutable.Map.empty[Double, scala.collection.mutable.ListBuffer[Double]]
    val res = new Array[Double](nbLines * 2)
    var curCPUActivity = 0.0

    for(i <- 0 until nbSamples - 1) {
      // Delete all the elements before the separator (useless synchronization step)
      var data = samplingFiles(i + 1).inputStream.lines().toArray.dropWhile(line => line != separator).tail
      curCPUActivity = 0.0

      // Get the median for each step messages
      for(j <- 0 until nbLines) {
        // Get the step messages (between 0 and separator)
        var tmpData = data.takeWhile(line => line != separator).map(_.toDouble)
        data = data.dropWhile(line => line != separator).tail
        acc(curCPUActivity) = acc.getOrElse(curCPUActivity, scala.collection.mutable.ListBuffer.empty[Double]) += median(tmpData)
        curCPUActivity += (1.0 / nbStep).toDouble
      }
    }

    curCPUActivity = 0.0

    // Compute the avg consumption per step
    for(i <- 0 until nbLines) {
      res(i) = curCPUActivity
      var sampleValues = acc(curCPUActivity)
      res(i + nbLines) = sampleValues.sum / sampleValues.size
      curCPUActivity += (1.0 / nbStep).toDouble
    }
    
    new DenseMatrix(nbLines, res)
  }

  /**
   * Least Squares method (see http://kobus.ca/seminars/ugrad/NM5_curve_s02.pdf)
   */
  def leastSquares(matrix: DenseMatrix[Double]): Array[Double] = {
    var degree: Int = 0
    var corrCoeff: Double = 0
    var coeffs = Array.empty[Double]

    do {
      degree += 1
      var xis = matrix(::, 0)
      var yis = matrix(::, 1)

      // Compute the xi^j sum (1 to degree + degree)
      //It allows to not compute the same xi^j many times in the matrix construction
      var sumXis = scala.collection.mutable.Map.empty[Int, Double]
      for(j <- 1 to (degree + degree)) {
          if(!sumXis.contains(j)) {
              sumXis(j) = xis.map(xi => math.pow(xi, j)).sum
          }
      }

      // First item, n represents the number of examples
      // It represents the line which contains all values
      var maxLine = scala.collection.mutable.ListBuffer[Double](xis.size)
      for(j <- 0 to (degree + degree - 1)) {
          maxLine += sumXis(j + 1)
      }

      // Just take the right values into max_line list (interval playing)
      var A = new scala.collection.mutable.ListBuffer[Double]
      for(j <- 0 to degree) {
          A ++= maxLine.slice(j, degree + j + 1)
      }

      // B list definition
      var B = scala.collection.mutable.ListBuffer[Double](yis.sum)
      for(j <- 1 to degree) {
         B += (xis.map(xi => math.pow(xi, j)) :* yis).sum
      }

      // Matrix definition, to do matrix computations with linealg
      var matA = new DenseMatrix(degree + 1, A.toArray)
      var matB = new DenseVector(B.toArray)
      // \ is a shortcut for A^-1 * B
      coeffs = (matA \ matB).toArray
      var fxi = new DenseVector((for(x <- xis.toArray) yield polyval(coeffs, x)))
      
      // Correlation coefficient (see http://en.wikipedia.org/wiki/Pearson_product-moment_correlation_coefficient)
      var yiMean = mean(yis)
      var yiStdDev = stddev(yis)
      var fxiMean = mean(fxi)
      var fxiStdDev = stddev(fxi)

      corrCoeff = math.abs(((yis :- yiMean) dot (fxi :- fxiMean)) / ((yis.size - 1) * yiStdDev * fxiStdDev))

      } while(corrCoeff < threshold)

      coeffs
  }
}

object PolynomialFitting extends SamplingConfiguration {

  lazy val outputConf = {
    Path.fromString("formula_cpu.conf").deleteIfExists()
    Resource.fromFile("formula_cpu.conf")
  }

  def compute() {
    val polyObj = new PolynomialFitting(corrCoeff)
    val matrix = polyObj.readMatrixFromFile
    val coeffs = polyObj.leastSquares(matrix)
    
    outputConf.append("powerapi {" + scalax.io.Line.Terminators.NewLine.sep)
    outputConf.append("  formula {" + scalax.io.Line.Terminators.NewLine.sep)
    outputConf.append("    coeffs = [" + scalax.io.Line.Terminators.NewLine.sep)
    for(coeff <- coeffs) {
        outputConf.append("      { value = "+coeff+" }" + scalax.io.Line.Terminators.NewLine.sep)
    }
    outputConf.append("    ]" + scalax.io.Line.Terminators.NewLine.sep)
    outputConf.append("  }" + scalax.io.Line.Terminators.NewLine.sep)
    outputConf.append("}" + scalax.io.Line.Terminators.NewLine.sep)
  }
}