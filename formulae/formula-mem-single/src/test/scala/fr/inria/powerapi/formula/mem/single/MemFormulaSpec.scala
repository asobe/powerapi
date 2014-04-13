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
package fr.inria.powerapi.formula.mem.single

import scala.concurrent.duration.DurationInt

import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.{FlatSpec, Matchers}
import fr.inria.powerapi.library.PowerAPI
import fr.inria.powerapi.core.Process
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import fr.inria.powerapi.formula.mem.api.MemFormulaMessage
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import fr.inria.powerapi.sensor.mem.api.MemSensorMessage
import fr.inria.powerapi.sensor.mem.sigar.MemSensor

class MemFormulaListener extends Actor {
  def receive = {
    case memFormulaMessage: MemFormulaMessage => println(memFormulaMessage.energy.power)
  }
}

@RunWith(classOf[JUnitRunner])
class MemFormulaSpec extends FlatSpec with Matchers with AssertionsForJUnit {

  trait ConfigurationMock extends Configuration {
    override lazy val readPower = 5.0
    override lazy val writePower = 15.0
  }

  implicit val system = ActorSystem("mem-formula-single")
  val memFormula = TestActorRef(new MemFormula with ConfigurationMock)

  "A MemFormula" should "compute global memory power consumption" in {
    memFormula.underlyingActor.power should equal ((5 + 15).doubleValue / 2)
  }

  "A MemFormula" should "compute process memory power consumption" in {
    memFormula.underlyingActor.compute(MemSensorMessage(residentPerc = 0.5, tick = null)) should equal (memFormula.underlyingActor.power * 0.5)
  }
}