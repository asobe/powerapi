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

package fr.inria.powerapi.sensor.libpfm;

import fr.inria.powerapi.core.{ Identifier, CID, TCID, TID }
import perfmon2.libpfm.{ LibpfmLibrary, perf_event_attr, pfm_perf_encode_arg_t, pfm_event_info_t, pfm_pmu_info_t, pfm_event_attr_info_t }
import perfmon2.libpfm.LibpfmLibrary.{ pfm_os_t, pfm_pmu_t, pfm_attr_t }

import org.bridj.Pointer
import org.bridj.Pointer.{ allocateCLongs, pointerTo, pointerToCString }
import org.apache.log4j.{ Level, Logger }
import scala.collection

/**
* This object allows us to interact with the Libpfm library (C Library).
* We use jnaerator and bridj to create the binding with it.
* https://github.com/ochafik/nativelibs4java
*
* The most configurable method is configureCounter (configuration parameter). To have more details about the bits to enable,
* follow this link: http://www.man7.org/linux/man-pages/man2/perf_event_open.2.html.
*/
object LibpfmUtil {
  // Shortcut to get the enabled and running time when we read the counters.
  private val perfFormatScale = LibpfmLibrary.perf_event_read_format.PERF_FORMAT_TOTAL_TIME_ENABLED.value.toInt | LibpfmLibrary.perf_event_read_format.PERF_FORMAT_TOTAL_TIME_RUNNING.value.toInt
  private lazy val logger = Logger.getLogger(this.getClass.getName)
  private var _isAlreadyInit = false

  def isAlreadyInit = _isAlreadyInit

  /**
   * Initializes libpfm. To use in first when you want to interact with the performance counters.
   */
  def initialize(): Boolean = {
    if(!isAlreadyInit) {
      val ret = LibpfmLibrary.pfm_initialize()
      
      if(ret == LibpfmLibrary.PFM_SUCCESS) {
        _isAlreadyInit = true
        true
      }
      else {
        _isAlreadyInit = false
        throw new RuntimeException("Libpfm can not be initialized.")
      }
    }
    else {
      if(logger.isEnabledFor(Level.DEBUG)) logger.debug("Libpfm is already initialized.")
      true
    }
  }

  /**
   * Allows to convert a bitset to the corresponding long.
   * @param set: sets of bits.
   */
  def convertBitsetToLong(bitset: java.util.BitSet): Long = {
    var long = 0L
    // We limit the size of the bitset (see the documentation on perf_event.h, only 23 bits for the config.)
    // The other 41 bits are reserved.
    val configuration = bitset.get(0, 23)

    // Here, the bit 21 is mandatory (exclude_guest bit), else libpfm does not work correctly.
    // NOTE: It's not required in the kernel 3.8.0-030800.
    //if(!configuration.get(20)) configuration.set(20)

    // Conversion
    for(i <- 0 until configuration.length) {
      configuration.get(i) match {
        case true => long += 1L << i
        case _ => long += 0L
      }
    }

    long
  }

  /**
   * Opens a file descriptor for the given event and identifier, with the configuration precised by
   * the BitSet.
   * @param identifier: identifier.
   * @param configuration: Set of bits used to configure the structure which will be used to initialize the counter.
   * @param name: event name.
   */
  def configureCounter(identifier: Identifier, configuration: java.util.BitSet, name: String): Option[Int] = {
    val cName = pointerToCString(name)
    val argEncoded = new pfm_perf_encode_arg_t
    var argEncodedPointer = pointerTo(argEncoded)
    val eventAttr = new perf_event_attr
    val eventAttrPointer = pointerTo(eventAttr)
    
    argEncoded.attr(eventAttrPointer)
    
    // Get the specific event encoding for the OS.
    // PFM_PLM3: measure at user level (including PFM_PLM2, PFM_PLM1).
    // PFM_PLM0: measure at kernel level.
    // PFM_PLMH: measure at hypervisor level.
    // PFM_OS_PERF_EVENT_EXT is used to extend the default perf_event library with libpfm.
    val ret = LibpfmLibrary.pfm_get_os_event_encoding(cName, LibpfmLibrary.PFM_PLM0|LibpfmLibrary.PFM_PLM3|LibpfmLibrary.PFM_PLMH, pfm_os_t.PFM_OS_PERF_EVENT, argEncodedPointer)

    if(ret == LibpfmLibrary.PFM_SUCCESS) {
      // Sets the bits in the structure.
      eventAttr.read_format(perfFormatScale)
      eventAttr.bits_config(convertBitsetToLong(configuration))

      // Opens the file descriptor.
      val fd = identifier match {
        case TID(tid) => CUtils.perf_event_open(eventAttrPointer, tid, -1, -1, 0)
        case CID(cid) => CUtils.perf_event_open(eventAttrPointer, -1, cid, -1, 0)
        case TCID(tid, cid) => CUtils.perf_event_open(eventAttrPointer, tid, cid, -1, 0)
        case _ => {
          if(logger.isEnabledFor(Level.ERROR)) logger.error("The type of the first parameter is unknown.")
          -1
        }
      }

      if(fd > 0) {
        Some(fd)
      }

      else {
        if(logger.isEnabledFor(Level.WARN)) logger.warn(s"Libpfm is not able to open a counter for the event $name.")
        None
      }
    }

    else {
      if(logger.isEnabledFor(Level.WARN)) logger.warn("Libpm can not initialize the structure for this event.")
      None
    }
  }

  /**
   * Resets the counter which is represented by the given file descriptor.
   * @param fd: file descriptor.
   */
  def resetCounter(fd: Int): Boolean = {
    CUtils.ioctl(fd, LibpfmLibrary.PERF_EVENT_IOC_RESET) == 0
  }

  /**
   * Enables the counter which is represented by the given file descriptor.
   * @param fd: file descriptor.
   */
  def enableCounter(fd: Int): Boolean = {
    CUtils.ioctl(fd, LibpfmLibrary.PERF_EVENT_IOC_ENABLE) == 0
  }

  /**
   * Disables the counter which is represented by the given file descriptor.
   * @param fd: file descriptor.
   */
  def disableCounter(fd: Int): Boolean = {
    CUtils.ioctl(fd, LibpfmLibrary.PERF_EVENT_IOC_DISABLE) == 0
  }

  /**
   * Closes the counter which is represented by the given file descriptor.
   * @param fd: file descriptor.
   */
  def closeCounter(fd: Int): Boolean = {
    CUtils.close(fd) == 0
  }

  /**
   * Reads the values from the given file descriptor.
   * @param fd: file descriptor.
   */
  def readCounter(fd: Int): Array[Long] = {
    val counts = allocateCLongs(3)
    // 8 bytes * 3 longs
    if(CUtils.read(fd, counts, 8 * 3) > -1) {
      counts.getCLongs()
    }
    else Array(0L, 0L, 0L)
  }


  /**
   * Allows to scale the results by applying a ratio between the enabled/running times
   * from the read and previous values.
   */
  def scale(now: Array[Long], old: Array[Long]): Long = {
   /* [0] = raw count
    * [1] = TIME_ENABLED
    * [2] = TIME_RUNNING
    */
    if(now(2) == 0 && now(1) == 0 && now(0) != 0) {
      if(logger.isEnabledFor(Level.WARN)) logger.warn("time_running = 0 = time_enabled, raw count not zero.")
    }
    if(now(2) > now(1)) {
      if(logger.isEnabledFor(Level.WARN)) logger.warn("time_running > time_enabled.")
    }
    if(now(2) - old(2) != 0) {
      // toDouble used to get the true ratio
      // round on the final value to obtain a Long
      ((now(0) - old(0)) * ((now(1) - old(1)) / (now(2) - old(2))).toDouble).round
    }
    else 0l
  }
  
  def getAllSpecificEvents = {
    /**
     * Returns the specifics PMU detected on the processor. All the generic PMUs are removed because they used the specifics ones for the encoding.
     */
    def specificPMUS = {
      // See the outputs of ./check_events from the examples folder in the libpfm library.
      val generics = Array(pfm_pmu_t.PFM_PMU_INTEL_X86_ARCH, pfm_pmu_t.PFM_PMU_PERF_EVENT, pfm_pmu_t.PFM_PMU_PERF_EVENT_RAW)
      val allSupportedPMUS = pfm_pmu_t.values().to[scala.collection.mutable.ArrayBuffer] -- generics
      val activePMUS = scala.collection.mutable.ArrayBuffer[pfm_pmu_info_t]()

      // The bit is_present is checked to know if a PMU is available or not. A shift is done because of a jnaerator/bridj limitation with bit fields struct.
      for(pmu <- allSupportedPMUS) {
        val pinfo = new pfm_pmu_info_t
        val pinfoPointer = pointerTo(pinfo)
        val ret = LibpfmLibrary.pfm_get_pmu_info(pmu, pinfoPointer)

        if(ret == LibpfmLibrary.PFM_SUCCESS && ((pinfo.bits_def >> 32) & 1) == 1) {
          activePMUS += pinfo
        }
      }
    
      activePMUS.toArray
    }
  
    /**
     * Get all the events contained in a PMU (with different UMASK).
     */
    def getEventsByPMU(pmu: pfm_pmu_info_t) = {
      val einfo = new pfm_event_info_t
      val einfoPointer = pointerTo(einfo)
      var i = pmu.first_event
      val events = scala.collection.mutable.ArrayBuffer[String]()

      while(i != -1) {
        if(LibpfmLibrary.pfm_get_event_info(i, pfm_os_t.PFM_OS_PERF_EVENT, einfoPointer) == LibpfmLibrary.PFM_SUCCESS) {
          // If there is no equivalent event, we can keep the event.
          if(einfo.equiv == null) {
            val eventName = einfo.name.getCString()
        
            // We keep only the events with at least one UMASK. The event with only modifiers are shortcuts.
            for(i <- 0 until einfo.nattrs) {
              val ainfo = new pfm_event_attr_info_t
              val ainfoPointer = pointerTo(ainfo)
              val ret = LibpfmLibrary.pfm_get_event_attr_info(einfo.idx, i, pfm_os_t.PFM_OS_PERF_EVENT, ainfoPointer)

              if(ret == LibpfmLibrary.PFM_SUCCESS) {
                // Filter on the type because it could be also a MODIFIER.
                if(ainfo.`type`.value == pfm_attr_t.PFM_ATTR_UMASK.value) {
                  events += eventName + ":" + ainfo.name.getCString()
                }
              }
            }
          }

          i = LibpfmLibrary.pfm_get_event_next(i)
        }
      }

      events.toArray
    }

    (for(pmu <- specificPMUS) yield(getEventsByPMU(pmu))).flatten
  }

  /**
   * Stop libpfm.
   */
  def terminate() = {
    LibpfmLibrary.pfm_terminate()
    _isAlreadyInit = false
  }
}
