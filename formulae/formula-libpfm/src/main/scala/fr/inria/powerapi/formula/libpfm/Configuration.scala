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
package fr.inria.powerapi.formula.libpfm

import fr.inria.powerapi.core.Configuration

import scala.collection.JavaConversions
import scala.collection.JavaConversions._

import com.typesafe.config.Config

trait LibpfmFormulaConfiguration extends Configuration {
  lazy val formulae = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.formulae")))
        yield (item.asInstanceOf[Config].getLong("freq"), JavaConversions.asScalaBuffer(item.asInstanceOf[Config].getDoubleList("formula").map(_.toDouble)).toArray)).toMap[Long, Array[Double]]
  } (Map[Long, Array[Double]]())

  lazy val events = (load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.events")))
        yield (item.asInstanceOf[Config].getString("event"))).toArray
  } (Array[String]())).sorted

  /** Thread numbers. */
  lazy val threads = load { _.getInt("powerapi.cpu.threads") }(0)
  /** Option used to know if cpufreq is enabled or not. */
  lazy val dvfs = load { _.getBoolean("powerapi.cpu.dvfs") }(false)
  /** Path to time_in_state file. */
  lazy val timeInStatePath = load { _.getString("powerapi.cpu.time-in-state") }("file:///sys/devices/system/cpu/cpu%?/cpufreq/stats/time_in_state")
}

trait LibpfmCoreCyclesFormulaConfiguration extends Configuration {
  val formulae = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.unhalted-cycles-formulae")))
        yield (item.asInstanceOf[Config].getDouble("coefficient"), JavaConversions.asScalaBuffer(item.asInstanceOf[Config].getDoubleList("formula").map(_.toDouble)).toArray)).toMap[Double, Array[Double]]
  } (Map[Double, Array[Double]]())
}
