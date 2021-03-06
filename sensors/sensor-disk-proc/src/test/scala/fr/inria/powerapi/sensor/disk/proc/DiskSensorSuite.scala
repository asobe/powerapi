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
package fr.inria.powerapi.sensor.disk.proc
import java.net.URL

import scala.concurrent.duration.DurationInt
import scala.util.Properties

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.Matchers

import akka.actor.actorRef2Scala
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.TestActorRef
import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.ask

import fr.inria.powerapi.core.{ ClockMessages, ClockSupervisor }
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.sensor.disk.api.DiskSensorMessage

class DiskReceiverMock extends Actor {
  var receivedValues: Option[DiskSensorMessage] = None

  def receive = {
    case diskSensorMessage: DiskSensorMessage => receivedValues = Some(diskSensorMessage)
  }
}

class DiskSensorSuite extends JUnitSuite with Matchers {
  trait ConfigurationMock extends Configuration {
    lazy val basedir = new URL("file", Properties.propOrEmpty("basedir"), "")
    override lazy val iofile = new URL(basedir, "/src/test/resources/proc/%?/io").toString
  }

  implicit val system = ActorSystem("DiskSensorSuite")
  implicit val tick = Tick(TickSubscription(1, Process(123), 1.second))
  val diskSensor = TestActorRef(new DiskSensor with ConfigurationMock)

  @Test
  def testReadAndWrite() {
    testReadAndWrite(diskSensor.underlyingActor.readAndwrite(tick.subscription.process))
  }

  private def testReadAndWrite(readAndWrite: (Long, Long)) {
    readAndWrite should equal((3309568, 567 - 36))
  }

  @Test
  def testTick() {
    import ClockMessages.{ StartClock, StopClock }
    implicit val timeout = Timeout(5.seconds)
    
    val diskReceiver = TestActorRef[DiskReceiverMock]
    val clock = system.actorOf(Props[ClockSupervisor])
    system.eventStream.subscribe(diskSensor, classOf[Tick])
    system.eventStream.subscribe(diskReceiver, classOf[DiskSensorMessage])

    val clockid = Await.result(clock ? StartClock(Array(Process(123)), 10.seconds), timeout.duration).asInstanceOf[Long]
    Thread.sleep(1000)
    Await.result(clock ? StopClock(clockid), timeout.duration)

    diskReceiver.underlyingActor.receivedValues match {
      case None => fail()
      case Some(diskSensorMessage) => testReadAndWrite(diskSensorMessage.rw("n/a"))
    }
  }
}
