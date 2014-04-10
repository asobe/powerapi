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
package fr.inria.powerapi.sensor.disk.atop

import scala.concurrent.duration.DurationInt
import java.io.IOException
import fr.inria.powerapi.sensor.disk.api.DiskSensorMessage
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.{ ClockMessages, ClockSupervisor }
import fr.inria.powerapi.core.TickSubscription
import scalax.io.Resource
import java.io.FileInputStream
import java.net.URL
import org.scalatest.junit.JUnitSuite
import org.scalatest.Matchers
import scala.util.Properties
import org.junit.Test
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.actor.Props
import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.ask
import akka.actor.Actor

trait ConfigurationMock extends Configuration {
  lazy val basedir = new URL("file", Properties.propOrEmpty("basedir"), "")
  override lazy val processStatPath = new URL(basedir, "/src/test/resources/proc/%?/stat").toString
}

class DiskSensorMock extends DiskSensor with ConfigurationMock

class DiskSensorReceiver extends Actor {
  val receivedData = collection.mutable.Map[String, (Long, Long)]()

  def receive = {
    case diskSensorMessage: DiskSensorMessage => process(diskSensorMessage)
  }

  def process(diskSensorMessage: DiskSensorMessage) {
    receivedData ++= diskSensorMessage.rw
  }
}

class DiskSensorSuite extends JUnitSuite with Matchers {
  @Test
  def testReadAndWrite() {
    implicit val system = ActorSystem("DiskSensorSuite")
    val diskSensor = TestActorRef[DiskSensorMock].underlyingActor

    diskSensor.readAndWrite(Process(123)) should equal((1, 3))
  }

  @Test
  def testTick() {
    import ClockMessages.{ StartClock, StopClock }
    implicit val timeout = Timeout(5.seconds)

    implicit val system = ActorSystem("DiskSensorSuite")
    val diskSensor = system.actorOf(Props[DiskSensorMock])
    val diskSensorReceiver = TestActorRef[DiskSensorReceiver]
    val clock = system.actorOf(Props[ClockSupervisor])
    system.eventStream.subscribe(diskSensor, classOf[Tick])
    system.eventStream.subscribe(diskSensorReceiver, classOf[DiskSensorMessage])

    val clockid = Await.result(clock ? StartClock(Array(Process(123)), 10.seconds), timeout.duration).asInstanceOf[Long]
    Thread.sleep(1000)
    Await.result(clock ? StopClock(clockid), timeout.duration)

    diskSensorReceiver.underlyingActor.receivedData should have size 1
    diskSensorReceiver.underlyingActor.receivedData("n/a") should equal((1.0, 3.0))
  }
}
