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

import fr.inria.powerapi.core.{ Energy, FormulaMessage, Message, Tick }
import fr.inria.powerapi.sensor.libpfm.{ Core, Counter, Event, LibpfmSensorMessage }
import fr.inria.powerapi.sensor.cpu.api.TimeInStates

import breeze.numerics.polyval

/**
 * LibpfmFormula messages.
 */
case class LibpfmListenerMessage(tick: Tick, timeInStates: TimeInStates = TimeInStates(Map[Int, Long]()), messages: List[LibpfmSensorMessage]) extends Message
case class LibpfmFormulaMessage(tick: Tick, energy: Energy, device: String = "cpu") extends FormulaMessage

/**
 * LibpfmCoreCyclesFormula Messages.
 * Only with CPU_CLK_UNHALTED:THREAD_P and CPU_CLK_UNHALTED:REF_P.
 */
case class LibpfmRowMessage(core: Core, counter: Counter, event: Event, tick: Tick) extends Message
case class LibpfmAggregatedMessage(tick: Tick, messages: collection.mutable.ListBuffer[LibpfmRowMessage] = collection.mutable.ListBuffer[LibpfmRowMessage]()) extends LibpfmCoreCyclesFormulaConfiguration with Message {
  def energy = {
    var p = 0d
    var maxCoefficient = formulae.keys.min

    for(byCore <- messages.groupBy(_.core)) {
      val unhaltedCyclesL = byCore._2.filter(_.event.name == "CPU_CLK_UNHALTED:THREAD_P")
      val refCyclesL = byCore._2.filter(_.event.name == "CPU_CLK_UNHALTED:REF_P")

      if(unhaltedCyclesL.size == 1 && refCyclesL.size == 1) {
        val unhaltedCycles = unhaltedCyclesL(0).counter.value / (tick.subscription.duration.toMillis / 1000)
        val refCycles = refCyclesL(0).counter.value / (tick.subscription.duration.toMillis / 1000)
        
        var coefficient = math.round(unhaltedCycles / refCycles.toDouble).toDouble
        if(coefficient.isNaN || coefficient < formulae.keys.min) coefficient = formulae.keys.min
        if(coefficient > formulae.keys.max) coefficient = formulae.keys.max
        if(!formulae.contains(coefficient)) {
          val coefficientsBefore = formulae.keys.filter(_ < coefficient)
          coefficient = coefficientsBefore.max
        }
        // For the idle used in the processor.
        if(coefficient > maxCoefficient) maxCoefficient = coefficient

        var formula = formulae(coefficient)
        formula(0) = 0d
        p += polyval(formula, unhaltedCycles)
      }
    }

    (maxCoefficient, Energy.fromPower(p))
  }

  def add(message: LibpfmRowMessage) {
    messages += message
  }

  def +=(message: LibpfmRowMessage) {
    add(message)
  }
}

case class LibpfmCoreCyclesFormulaMessage(tick: Tick, energy: Energy, maxCoefficient: Double, device: String = "cpu") extends FormulaMessage
