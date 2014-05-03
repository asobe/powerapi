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
package fr.inria.powerapi.sensor.cpu.proc.reg

import java.net.URL

import scala.concurrent.duration.DurationInt

import scala.util.Properties

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.FlatSpec
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.sensor.cpu.api.ActivityPercent

@RunWith(classOf[JUnitRunner])
class CpuSensorSpec extends FlatSpec with Matchers {

  trait ConfigurationMock extends fr.inria.powerapi.sensor.cpu.proc.Configuration {
    lazy val basedir = new URL("file", Properties.propOrEmpty("basedir"), "")
    override lazy val globalStatPath = new URL(basedir, "/src/test/resources/proc/stat").toString
  }

  implicit val system = ActorSystem("cpusensorsuite")
  val cpuSensor = TestActorRef(new CpuSensor with ConfigurationMock)
  val tick = Tick(1, TickSubscription(1, Process(123), 1.second))
  val splittedTimes: Array[Long] = Array(441650, 65, 67586, 3473742, 31597, 0, 7703, 0, 23, 22)
  val globalElapsedTime   = 441650 + 65 + 67586 + 3473742 + 31597 + 0 + 7703 + 0
  val activityElapsedTime = 441650 + 65 + 67586                              + 0

  "A CpuSensor" should "read activity elapsed time from a given dedicated system file" in {
    cpuSensor.underlyingActor.activityPercent.activityElapsedTime(splittedTimes, globalElapsedTime) should equal(activityElapsedTime)
  }

  "A CpuSensor" should "refresh its cache after each activityPercent calls" in {
    cpuSensor.underlyingActor.activityPercent.cache should have size 0
    cpuSensor.underlyingActor.activityPercent.process(tick.subscription)
    cpuSensor.underlyingActor.activityPercent.cache should equal(Map(tick.subscription -> (globalElapsedTime, activityElapsedTime)))
  }

  "A CpuSensor" should "compute the CPU percent activity" in {
    val oldActivityElapsedTime = activityElapsedTime / 2
    val oldGlobalElapsedTime = globalElapsedTime / 2
    cpuSensor.underlyingActor.activityPercent.refrechCache(tick.subscription, (oldGlobalElapsedTime, oldActivityElapsedTime))
    cpuSensor.underlyingActor.activityPercent.process(tick.subscription) should equal(
      ActivityPercent((activityElapsedTime - oldActivityElapsedTime).doubleValue() / (globalElapsedTime - oldGlobalElapsedTime))
    )
  }

}
