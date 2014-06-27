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
package fr.inria.powerapi.sensor.cpu.proc.virtio

import scala.concurrent.duration.DurationInt

import scala.util.Properties

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.FlatSpec
import akka.actor.ActorSystem
import org.scalatest.mock.MockitoSugar._
import org.mockito.Mockito.when

import fr.inria.powerapi.core.Energy

class CpuSensorMock(vmConsumption: VMConsumptionFromHost) extends CpuSensor

@RunWith(classOf[JUnitRunner])
class CpuSensorSpec extends FlatSpec with Matchers {

  "A VMConsumptionFromHost component" should "read the consumption inside a BufferReader one time per tick" in {
    implicit val system = ActorSystem("PowerAPISuite")
    val bufferMocked = mock[java.io.BufferedReader]

    val vmConsumptionMock = new VMConsumptionFromHost {
      override lazy val br = bufferMocked
    }

    when(bufferMocked.readLine).thenReturn("15", "16")
    vmConsumptionMock.process(1) should equal(Energy.fromPower(15))
    vmConsumptionMock.process(1) should equal(Energy.fromPower(15))
    vmConsumptionMock.cache.keys should contain(1)
    vmConsumptionMock.cache(1) should equal(15.0)
    vmConsumptionMock.process(2) should equal(Energy.fromPower(16))
    vmConsumptionMock.cache.keys should not contain(1)
    vmConsumptionMock.cache.keys should contain(2)
    vmConsumptionMock.cache(2) should equal(16.0)
  }
}