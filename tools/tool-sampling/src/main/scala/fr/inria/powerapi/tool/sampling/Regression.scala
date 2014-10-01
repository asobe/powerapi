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

import breeze.linalg._
import breeze.numerics._
import scalax.file.Path
import scalax.io.Resource

/*object PolynomialRegression extends Configuration {
  /**
   * Allows to read a matrix written in a file represented by its path.
   */
  private def readMatrixFromPath(path: String) = {
    csvread(file = new java.io.File(path),
      separator = csvDelimiter.charAt(0),
      skipLines = 1)
  }

  /**
   * Least Squares method (see http://kobus.ca/seminars/ugrad/NM5_curve_s02.pdf).
   * Works currently with one dependant variable (x).
   */
  private def leastSquaresMethod(matrix: DenseMatrix[Double], frequency: Long): Array[Double] = {
    var degree = 2
    var xis = matrix(::, 0)
    var yis = matrix(::, 1)

    // Compute the xi^j sum (1 to degree + degree).
    // It allows to not compute the same xi^j many times in the matrix construction.
    var sumXis = scala.collection.mutable.Map.empty[Int, Double]
    for(j <- 1 to (degree + degree)) {
      if(!sumXis.contains(j)) {
        sumXis(j) = sum(xis.map(xi => math.pow(xi, j)))
      }
    }

    // It represents the line which contains all values.
    var maxLine = scala.collection.mutable.ListBuffer[Double](xis.size)
    for(j <- 0 to (degree + degree - 1)) {
      maxLine += sumXis(j + 1)
    }

    // Just take the right values into max_line list (with interval).
    var A = new scala.collection.mutable.ListBuffer[Double]
    for(j <- 0 to degree) {
      A ++= maxLine.slice(j, degree + j + 1)
    }

    var B = scala.collection.mutable.ListBuffer[Double](sum(yis))
    for(j <- 1 to degree) {
      B += sum(xis.map(xi => math.pow(xi, j)) :* yis)
    }

    // Matrix computations with linealg
    var matA = new DenseMatrix(degree + 1, A.toArray)
    var matB = new DenseVector(B.toArray)
    // \ is a shortcut for A^-1 * B
    val coeffs = (matA \ matB).toArray
    
    // Exponations for the error computations: http://www.stat.purdue.edu/~xuanyaoh/stat350/xyApr6Lec26.pdf
    val estimatedYis = new DenseVector((for(x <- xis.toArray) yield polyval(coeffs, x))) 
    val sst = sum(pow((yis - mean(yis)), 2))
    val sse = sum(pow((yis - estimatedYis), 2))
    val rsquared = 1 - (sse / sst)
    val mse = sse / xis.size
    val se = sqrt(mse)
    
    println(s"----- F = $frequency -----")
    println(s"r^2: $rsquared")
    println(s"Mean squared error: $mse")
    println(s"Standard deviation: $se")
    
    // [0] => Intercept, [1] => x^1, [2] => x^2
    coeffs
  }
 
  def run() = {
    implicit val codec = scalax.io.Codec.UTF8

    // Cleaning phase
    Path.fromString(formulaePath).deleteRecursively(force = true)
    if(!Path.fromString(processingPath).exists) System.exit(0)
 
    val frequencies = Util.availableFrequencies.getOrElse(Array(defaultFrequency))
    val idlePowers = scala.collection.mutable.ArrayBuffer[Double]()
    val lines = scala.collection.mutable.ArrayBuffer[String]()
    lines += "# formula = [intercept, x, x^2]" + scalax.io.Line.Terminators.NewLine.sep
    lines += "powerapi.libpfm.formulae = [" + scalax.io.Line.Terminators.NewLine.sep
    
    for(frequency <- frequencies) {
      val countersMatrix = readMatrixFromPath(s"$processingPath/$frequency/counters.csv")
      val powersMatrix = readMatrixFromPath(s"$processingPath/$frequency/powers.csv")
      val idlePowerMatrix = readMatrixFromPath(s"$processingPath/$frequency/idle-power.csv")
      
      val matrix = DenseMatrix.horzcat(countersMatrix, powersMatrix)
      val coefficients = leastSquaresMethod(matrix, frequency)
      lines += "\t{freq = " + frequency.toString + ", formula = [" + coefficients.mkString(",") + "]}" + scalax.io.Line.Terminators.NewLine.sep

      idlePowers += idlePowerMatrix(0, 0)
    }
 
    lines += "]" + scalax.io.Line.Terminators.NewLine.sep + "" + scalax.io.Line.Terminators.NewLine.sep
    lines += "powerapi.libpfm.idle-power = " + Util.median(idlePowers)
 
    lines.foreach(line => Resource.fromFile(s"$formulaePath/libpfm-formula.conf").append(line))
  }
}*/
