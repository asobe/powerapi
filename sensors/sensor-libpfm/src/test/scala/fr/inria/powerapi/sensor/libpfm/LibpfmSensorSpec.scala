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

import akka.testkit.TestActorRef
import akka.actor.{ Actor, ActorSystem, Props }

import scala.concurrent.duration.{ FiniteDuration, Duration, DurationInt }

import java.lang.management.ManagementFactory

import fr.inria.powerapi.core.{ Tick, TickSubscription, Process }

class Listener extends Actor {
  var received = 0
  
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmSensorMessage])
  }

  def receive() = {
    case _: LibpfmSensorMessage => received += 1
    case unknown => println("umh, I can not process this message " + unknown + ".")
  }
}

@RunWith(classOf[JUnitRunner])
class LibpfmSensorSpec extends FlatSpec with Matchers {
  val currentPid = ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt
  implicit val system = ActorSystem("LibpfmTest")

  val bitset = new java.util.BitSet()
  bitset.set(0)
  bitset.set(1)
  bitset.set(5)
  bitset.set(6)

  val libpfmSensor = TestActorRef(new LibpfmSensor("instructions", bitset))
  val listener = TestActorRef[Listener]

  val m1 = Tick(1, TickSubscription(Process(currentPid), 1.seconds), 1)
  val error = Tick(1, TickSubscription(Process(-1), 1.seconds), 1)

  "A LibpfmSensor" should "process a Tick message" in {
    libpfmSensor.underlyingActor.process(m1)
    libpfmSensor.underlyingActor.process(error)
    listener.underlyingActor.received should equal(1)
  }
}