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

trait Configuration extends fr.inria.powerapi.core.Configuration with fr.inria.powerapi.sensor.libpfm.LibpfmConfiguration {
  /** Thread numbers. */
  lazy val threads = load { _.getInt("powerapi.cpu.threads") }(0)
  /** Cache available (all levels, only for the data) in KB. */
  lazy val l3Cache = load { _.getInt("powerapi.cpu.L3-cache") }(0)
  /** Option used to know if cpufreq is enable or not. */
  lazy val cpuFreq = load { _.getBoolean("powerapi.cpu.cpufreq-utils") }(false)
  /** Number of samples .*/
  lazy val samples = load { _.getInt("powerapi.tool.sampling.samples") }(0)
  /** Number of required messages per step. */
  lazy val nbMessages = load { _.getInt("powerapi.tool.sampling.step.messages") }(0)
  /** Path used to store the files created during the sampling. */
  lazy val samplingPath = load { _.getString("powerapi.tool.sampling.path") }("samples")
  /** Path used to store the processed files, used to compute the final formulae. */
  lazy val processingPath = load { _.getString("powerapi.tool.processing.path") }("pr-data")
  /** Path used to store the formulae computed by a multiple linear regression. */
  lazy val formulaePath = load { _.getString("powerapi.tool.formulae.path") }("formulae")
  /**
   * Scaling frequencies information, giving information about the available frequencies for each core.
   * This information is typically given by the cpufrequtils utils.
   *
   * @see http://www.kernel.org/pub/linux/utils/kernel/cpufreq/cpufreq-info.html
   */
  lazy val scalingFreqPath = load { _.getString("powerapi.cpu.scaling-available-frequencies") }("/sys/devices/system/cpu/cpu%?/cpufreq/scaling_available_frequencies")
  /** Default values for the output files. */
  lazy val outBasePathLibpfm = "output-libpfm-"
  lazy val outPathPowerspy = "output-powerspy.dat"
  lazy val separator = "======="
  /** Default values for data processing. */
  lazy val elements = Array("cache", "cpu")
  lazy val eltIdlePower = "cpu"
  lazy val csvDelimiter = ";"
  /** Default value when cpufreq-utils is disable (used to create the directory hierarchy). */
  lazy val defaultFrequency = 0L
}