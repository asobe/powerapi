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

package fr.inria.powerapi.sensor.libpfm

import scala.collection.JavaConversions
import com.typesafe.config.Config

/**
 * Libpfm sensor configuration.
 */
trait LibpfmConfiguration extends fr.inria.powerapi.core.Configuration {
  /** Read all bit fields from the configuration. */
  lazy val bits = {
    collection.immutable.HashMap[Int, Int](
      0 -> load { _.getInt("powerapi.libpfm.configuration.disabled") }(0),
      1 -> load { _.getInt("powerapi.libpfm.configuration.inherit") }(0),
      2 -> load { _.getInt("powerapi.libpfm.configuration.pinned") }(0),
      3 -> load { _.getInt("powerapi.libpfm.configuration.exclusive") }(0),
      4 -> load { _.getInt("powerapi.libpfm.configuration.exclude_user") }(0),
      5 -> load { _.getInt("powerapi.libpfm.configuration.exclude_kernel") }(0),
      6 -> load { _.getInt("powerapi.libpfm.configuration.exclude_hv") }(0),
      7 -> load { _.getInt("powerapi.libpfm.configuration.exclude_idle") }(0),
      8 -> load { _.getInt("powerapi.libpfm.configuration.mmap") }(0),
      9 -> load { _.getInt("powerapi.libpfm.configuration.comm") }(0),
      10 -> load { _.getInt("powerapi.libpfm.configuration.freq") }(0),
      11 -> load { _.getInt("powerapi.libpfm.configuration.inherit_stat") }(0),
      12 -> load { _.getInt("powerapi.libpfm.configuration.enable_on_exec") }(0),
      13 -> load { _.getInt("powerapi.libpfm.configuration.task") }(0),
      14 -> load { _.getInt("powerapi.libpfm.configuration.watermark") }(0),
      15 -> load { _.getInt("powerapi.libpfm.configuration.precise_ip_1") }(0),
      16 -> load { _.getInt("powerapi.libpfm.configuration.precise_ip_2") }(0),
      17 -> load { _.getInt("powerapi.libpfm.configuration.mmap_data") }(0),
      18 -> load { _.getInt("powerapi.libpfm.configuration.sample_id_all") }(0),
      19 -> load { _.getInt("powerapi.libpfm.configuration.exclude_host") }(0),
      20 -> load { _.getInt("powerapi.libpfm.configuration.exclude_guest") }(0),
      21 -> load { _.getInt("powerapi.libpfm.configuration.exclude_callchain_kernel") }(0),
      22 -> load { _.getInt("powerapi.libpfm.configuration.exclude_callchain_user") }(0)
    )
  }

  /** Create the corresponding BitSet used to open the file descriptor. */
  lazy val bitset = {
    val tmp = new java.util.BitSet()
    bits.filter { 
      case (idx, bit) => bit == 1
    }.keys.foreach(idx => tmp.set(idx))
    
    tmp
  }

  /** Events to monitor. */
  lazy val events = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.events")))
        yield (item.asInstanceOf[Config].getString("event"))).toArray
  }(Array[String]())
}

/**
* Libpfm sensor configuration.
*/
trait LibpfmCoreConfiguration extends LibpfmConfiguration {
  /** Thread numbers. */
  lazy val threads = load { _.getInt("powerapi.cpu.threads") }(0)
}