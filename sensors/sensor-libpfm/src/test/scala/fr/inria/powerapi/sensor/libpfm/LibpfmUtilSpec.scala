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

import scala.concurrent.duration.DurationInt

import scala.util.Properties

import org.junit.runner.RunWith
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}
import org.scalatest.Matchers
import org.scalatest.FlatSpec

// import akka.actor.ActorSystem
// import akka.testkit.TestActorRef
// import fr.inria.powerapi.core.Process
// import fr.inria.powerapi.core.Tick
// import fr.inria.powerapi.core.TickSubscription
// import fr.inria.powerapi.sensor.cpu.api.ProcessPercent

@RunWith(classOf[JUnitRunner])
class LibpfmUtilSpec extends FlatSpec with Matchers {

  "Init. method" should "initiliaze the libpfm" in {
    LibpfmUtil.isAlreadyInit should equal(false)
    val init = LibpfmUtil.initialize()
    LibpfmUtil.isAlreadyInit should equal(true)
    init should equal(true)
    LibpfmUtil.initialize()
    LibpfmUtil.isAlreadyInit should equal(true)
  }
  // trait ConfigurationMock extends Configuration {
  //   lazy val basedir = new URL("file", Properties.propOrEmpty("basedir"), "")
  //   override lazy val globalStatPath = new URL(basedir, "/src/test/resources/proc/stat").toString
  //   override lazy val processStatPath = new URL(basedir, "/src/test/resources/proc/%?/stat").toString
  // }

  // implicit val system = ActorSystem("cpusensorsuite")
  // val cpuSensor = TestActorRef(new CpuSensor with ConfigurationMock)
  // val tick = Tick(1, TickSubscription(Process(123), 1.second))
  // val processElapsedTime = 2 + 2
  // val splittedTimes: Array[Long] = Array(441650, 65, 67586, 3473742, 31597, 0, 7703, 0, 23, 22)
  // val globalElapsedTime = 441650 + 65 + 67586 + 3473742 + 31597 + 0 + 7703 + 0

  // "A CpuSensor" should "read global elapsed time from a given dedicated system file" in {
  //   cpuSensor.underlyingActor.processPercent.globalElapsedTime(splittedTimes) should equal(globalElapsedTime)
  // }

  // "A CpuSensor" should "read process elapsed time from a given dedicated system file" in {
  //   cpuSensor.underlyingActor.processPercent.processElapsedTime(Process(123)) should equal(processElapsedTime)
  // }

  // "A CpuSensor" should "refresh its cache after each processPercent calls" in {
  //   cpuSensor.underlyingActor.processPercent.cache should have size 0
  //   cpuSensor.underlyingActor.processPercent.process(tick.subscription)
  //   cpuSensor.underlyingActor.processPercent.cache should equal(Map(tick.subscription -> (processElapsedTime, globalElapsedTime)))
  // }

  // "A CpuSensor" should "compute the process CPU percent" in {
  //   val oldProcessElapsedTime = processElapsedTime / 2
  //   val oldGlobalElapsedTime = globalElapsedTime / 2
  //   cpuSensor.underlyingActor.processPercent.refrechCache(tick.subscription, (oldProcessElapsedTime, oldGlobalElapsedTime))
  //   cpuSensor.underlyingActor.processPercent.process(tick.subscription) should equal(
  //     ProcessPercent((processElapsedTime - oldProcessElapsedTime).doubleValue() / (globalElapsedTime - oldGlobalElapsedTime))
  //   )
  // }
}