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
package fr.inria.powerapi.formula.cpu.reg

import org.scalatest.FlatSpec
import org.scalatest.junit.ShouldMatchersForJUnit
import scala.collection.mutable.Stack
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.pattern.ask
import fr.inria.powerapi.core.Listener
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage
import fr.inria.powerapi.sensor.cpu.api.ProcessElapsedTime
import fr.inria.powerapi.sensor.cpu.api.GlobalElapsedTime
import fr.inria.powerapi.sensor.cpu.api.TimeInStates
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import akka.util.Timeout
import fr.inria.powerapi.sensor.cpu.api.ProcessPercent
import fr.inria.powerapi.sensor.cpu.api.ActivityPercent

@RunWith(classOf[JUnitRunner])
class CpuFormulaSpec extends FlatSpec with ShouldMatchersForJUnit {

  implicit val system = ActorSystem("CpuFormulaSpecSystem")

  "A CpuFormula" should "be configured with a given coeffs" in {
    val cpuFormula = TestActorRef(new CpuFormula())

    cpuFormula.underlyingActor.coeffs should equal(Array(-6.7, 133.2, -147.1, 55.2))
  }

  "A CpuFormula" should "be able to compute the CPU energy of a given process" in {
    val cpuFormula = TestActorRef(new CpuFormula())
    val CPUpower = (-6.7
                    + (133.2 * 0.5)
                    - (147.1 * math.pow(0.5, 2))
                    + (55.2 * math.pow(0.5, 3)))

    cpuFormula.underlyingActor.compute(
      CpuSensorMessage(
        processPercent = ProcessPercent(0.1),
        activityPercent = ActivityPercent(0.5),
        tick = null
      )
    ) should equal(Energy.fromPower(((0.1 * CPUpower).doubleValue()) / 0.5))
  }

}
