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

import scalax.io.Resource

import breeze.linalg._
import breeze.numerics._


/**
 * Util class for polynomial regression
 * @author mcolmant
 */
class PolynomialFitting(var threshold: Double) {
  //Data sampling configuration part
  lazy val conf = ConfigFactory.load
  
  lazy val nbCore = conf.getInt("powerapi.cpu.core")
  
  //Number of message returned by PowerSpy required for the computation of the power average
  lazy val nbMessage = conf.getInt("powerapi.tool.sampling.message.count")
  
  //The increase of the stress activity at each step
  lazy val stressActivityStep = conf.getInt("powerapi.tool.sampling.stress.activity-step")

  // threshold must be between -1 and 1, but we use the abs value
  if(threshold < 0) {
      // default value
      threshold = abs(threshold)
  }

  if(threshold > 1) {
      threshold = 1
  }


  /**
   * Read data sampling file performed by PowerSpy monitoring
   */
  def readMatrixFromFile(filepath: String): DenseMatrix[Double] = {
    val data = scala.io.Source.fromFile(filepath).getLines.toArray.map(_.toDouble)
    val nbStep = nbCore*(100/stressActivityStep).toInt
    val res = new Array[Double](nbStep*2)
    var j = 0
    var curCPUActivity = 0.0
    var sumPower = 0.0
    
    
    // compute the energy consumption when the CPU is idle
    for (i <- 0 to nbMessage) {
      sumPower += data(i)
    }

    val powerIdle = sumPower/nbMessage
    curCPUActivity += (1.0/nbStep).toDouble
    sumPower = 0.0
    
    // compute the average energy consumption at each stress step
    for (i <- nbMessage until data.size-1) {
      sumPower += data(i)
      if ((i+1)%nbMessage == 0) {
        res(j) = curCPUActivity
        curCPUActivity += (1.0/nbStep).toDouble
        res(j+1) = (sumPower/nbMessage)-powerIdle
        if (res(j+1) < 0.0) res(j+1) = 0.0
        sumPower = 0.0
        j += 2
      }
    }
    
    // 2 is the number of lines, fixed because of x/y (two dimensions)
    // We use matrix transpose to get a (nbLines x 2) matrix for computations
    new DenseMatrix(2, res).t
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

object PolynomialFitting {

  lazy val output = {
    val formulaFile = new java.io.File("formula-cpu.conf")

    if(formulaFile.exists()) {
      formulaFile.delete()
    }

    Resource.fromFile(formulaFile)
  }

  def compute() {
    val polyObj = new PolynomialFitting(0.996)
    val matrix = polyObj.readMatrixFromFile("powerapi-sampling.dat")
    val coeffs = polyObj.leastSquares(matrix)
    
    output.append("powerapi {" + scalax.io.Line.Terminators.NewLine.sep)
    output.append("  formula {" + scalax.io.Line.Terminators.NewLine.sep)
    output.append("    coeffs = [" + scalax.io.Line.Terminators.NewLine.sep)
    for(coeff <- coeffs) {
        output.append("      { value = "+coeff+" }" + scalax.io.Line.Terminators.NewLine.sep)
    }
    output.append("    ]" + scalax.io.Line.Terminators.NewLine.sep)
    output.append("  }" + scalax.io.Line.Terminators.NewLine.sep)
    output.append("}" + scalax.io.Line.Terminators.NewLine.sep)
  }
}