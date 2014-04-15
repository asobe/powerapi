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

import perfmon2.libpfm.LibpfmLibrary

import org.apache.log4j.{ Level, Logger }

object LibpfmUtil {
    // Shortcut to get the enabled and running time when we read the counters.
    private lazy val perfFormatScale = LibpfmLibrary.perf_event_read_format.PERF_FORMAT_TOTAL_TIME_ENABLED.value.toInt | 
                               LibpfmLibrary.perf_event_read_format.PERF_FORMAT_TOTAL_TIME_RUNNING.value.toInt
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
            if(logger.isEnabledFor(Level.WARN)) logger.warn("Libpfm is already initialized.")
            true
        }
    }
}