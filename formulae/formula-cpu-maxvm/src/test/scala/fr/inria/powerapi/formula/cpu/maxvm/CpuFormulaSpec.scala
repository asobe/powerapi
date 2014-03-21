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
package fr.inria.powerapi.formula.cpu.maxvm

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.AssertionsForJUnit
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
import fr.inria.powerapi.sensor.cpu.proc.virtio.CpuVirtioSensorMessage
import fr.inria.powerapi.sensor.cpu.api.ProcessElapsedTime
import fr.inria.powerapi.sensor.cpu.api.GlobalElapsedTime
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage
import akka.util.Timeout
import fr.inria.powerapi.sensor.cpu.api.ProcessPercent
import fr.inria.powerapi.sensor.cpu.api.ActivityPercent

@RunWith(classOf[JUnitRunner])
class CpuFormulaSpec extends FlatSpec with Matchers with AssertionsForJUnit {

  implicit val system = ActorSystem("CpuFormulaSpecSystem")

  "A CpuFormula" should "be able to compute the CPU energy of a given process" in {
    val cpuFormula = TestActorRef(new CpuFormula())
    val vmConsumption = 22.50

    cpuFormula.underlyingActor.compute(
      CpuVirtioSensorMessage(
        processPercent = ProcessPercent(0.5),
        activityPercent = ActivityPercent(0.47),
        vmConsumption = Energy.fromPower(22.50),
        tick = null
      )
    ) should equal(Energy.fromPower(((0.5 * vmConsumption).doubleValue()) / 0.47))
  }

}
