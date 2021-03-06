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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.FlatSpec
import org.scalatest.BeforeAndAfter

import akka.testkit.TestActorRef
import akka.actor.ActorSystem

import scala.concurrent.duration.{ FiniteDuration, Duration, DurationInt }

import java.lang.management.ManagementFactory

import fr.inria.powerapi.core.{ Tick, TickSubscription, Process }

// TODO: Improve the tests coverage.
@RunWith(classOf[JUnitRunner])
class LibpfmCoreSensorSpec extends FlatSpec with Matchers with BeforeAndAfter {
  val currentPid = ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt
  implicit val system = ActorSystem("LibpfmTest")

  val bitset = new java.util.BitSet()

  val libpfmCore0Sensor0 = TestActorRef(new LibpfmCoreSensor("instructions", bitset, 0, Array(0)))
  val libpfmCore0Sensor1 = TestActorRef(new LibpfmCoreSensor("cycles", bitset, 0, Array(0))) 
  val listener = TestActorRef[Listener]

  before {
    LibpfmUtil.initialize()
  }

  after {
    LibpfmUtil.terminate()
  }

  "A LibpfmCoreSensor" should "have to be configured" in {
    val bitset = new java.util.BitSet()
    libpfmCore0Sensor0.underlyingActor.bitset should equal(bitset)
    libpfmCore0Sensor0.underlyingActor.osIndexes should equal(Array(0))
    libpfmCore0Sensor1.underlyingActor.bitset should equal(bitset)
    libpfmCore0Sensor1.underlyingActor.osIndexes should equal(Array(0))
  }

  "A LibpfmCoreSensor" should "process a Tick message" in {
    val m1 = Tick(TickSubscription(1, Process(currentPid), 1.seconds), 1)
    libpfmCore0Sensor0.underlyingActor.process(m1)
    libpfmCore0Sensor1.underlyingActor.process(m1)
    listener.underlyingActor.received should equal(2)
  }
}
