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

import java.net.URL

import org.scalatest.junit.JUnitSuite
import org.scalatest.Matchers
import org.junit.Test

import scalax.io.Resource
import scalax.file.Path

import scala.util.Properties

trait ProcessingConfigurationMock extends Configuration {
  override lazy val samplingPath = "src/test/resources/samples"
  override lazy val processingPath = "test-pr-data"
  override lazy val formulaePath = "test-formulae"
  override lazy val scalingFreqPath = "src/test/resources/sys/devices/system/cpu/cpu%?/cpufreq/scaling_available_frequencies"
}

trait RegressionConfigurationMock extends ProcessingConfigurationMock {
  override lazy val processingPath = "src/test/resources/pr-data"
}

class ProcessingMock extends Processing with ProcessingConfigurationMock
class RegressionMock extends MultipleLinearRegression with RegressionConfigurationMock

class SamplingSuite extends JUnitSuite with Matchers with ProcessingConfigurationMock {
 val availableFreqs = scala.collection.mutable.SortedSet[Long]()
    
  // Get the available frequencies from sys virtual filesystem.
  (for(thread <- 0 until threads) yield (scalingFreqPath.replace("%?", thread.toString))).foreach(filepath => {
    availableFreqs ++= scala.io.Source.fromFile(filepath).mkString.trim.split(" ").map(_.toLong)
  })

  @Test
  def testProcessing() = {
    new ProcessingMock().run()

    for(frequency <- availableFreqs) {
      val countersFile = Path.fromString(s"$processingPath/$frequency/counters.csv")
      val idleFile = Path.fromString(s"$processingPath/$frequency/idle.csv")
      val powersFile = Path.fromString(s"$processingPath/$frequency/power.csv")
      val countersFileChck = Path.fromString(s"src/test/resources/pr-data/$frequency/counters.csv")
      val idleFileChck = Path.fromString(s"src/test/resources/pr-data/$frequency/idle.csv")
      val powersFileChck = Path.fromString(s"src/test/resources/pr-data/$frequency/power.csv")
      countersFile.lines().toList should contain theSameElementsInOrderAs countersFileChck.lines().toList
    }

    Path.fromString(processingPath).deleteRecursively(force = true)
    Thread.sleep(1)
  }

  @Test
  def testRegression() = {
    new RegressionMock().run()

    val formulaFile = Path.fromString(s"$formulaePath/libpfm-formula.conf")
    val formulaFileChck = Path.fromString("src/test/resources/formulae/libpfm-formula.conf")
    formulaFile.lines().toList should contain theSameElementsInOrderAs formulaFileChck.lines().toList

    Path.fromString(formulaePath).deleteRecursively(force = true)
    Thread.sleep(1)
  }
}