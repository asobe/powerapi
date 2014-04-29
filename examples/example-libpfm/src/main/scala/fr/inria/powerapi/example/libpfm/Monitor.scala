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
package fr.inria.powerapi.example.libpfm

import scala.concurrent.duration.DurationInt

object Monitor extends App {
    fr.inria.powerapi.sensor.libpfm.LibpfmUtil.initialize()
    val api = new fr.inria.powerapi.library.PAPI with fr.inria.powerapi.sensor.libpfm.SensorLibpfm
                                                with fr.inria.powerapi.formula.libpfm.FormulaLibpfm
                                                with fr.inria.powerapi.processor.aggregator.device.AggregatorDevice                                    
                                                with fr.inria.powerapi.sensor.powerspy.SensorPowerspy
                                                with fr.inria.powerapi.formula.powerspy.FormulaPowerspy

    api.start(fr.inria.powerapi.library.ALL(), 1.seconds).attachReporter(classOf[fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter]).waitFor(10.minutes)
    api.stop()
    fr.inria.powerapi.sensor.libpfm.LibpfmUtil.terminate()
}