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
package fr.inria.powerapi.formula.libpfm

import fr.inria.powerapi.core.{ Energy, Process, Tick, TickSubscription }
import fr.inria.powerapi.sensor.libpfm.{ Counter, Event, LibpfmSensorMessage }
import fr.inria.powerapi.sensor.cpu.api.TimeInStates

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.AssertionsForJUnit
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import scala.concurrent.duration.DurationInt

trait ConfigurationMock extends Configuration {
  override lazy val timeInStatePath = "src/test/resources/sys/devices/system/cpu/cpu%?/cpufreq/stats/time_in_state"
}

class ListenerMessageListener extends akka.actor.Actor {
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmListenerMessage])
  }

  val cache = new scala.collection.mutable.HashMap[Long, scala.collection.mutable.ArrayBuffer[LibpfmListenerMessage]]

  def receive() = {
    case libpfmListenerMessage: LibpfmListenerMessage => {
      val entry = cache.getOrElse(libpfmListenerMessage.tick.clockid, scala.collection.mutable.ArrayBuffer[LibpfmListenerMessage]())
      entry += libpfmListenerMessage
      cache(libpfmListenerMessage.tick.clockid) = entry
    }
    case _ => println("ooops ...")
  }
}

class FormulaMessageListener extends akka.actor.Actor {
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmFormulaMessage])
  }

  val messages = new scala.collection.mutable.ArrayBuffer[LibpfmFormulaMessage]

  def receive() = {
    case libpfmFormulaMessage: LibpfmFormulaMessage => {
      messages += libpfmFormulaMessage
    }
    case _ => println("ooops ...")
  }
}

@RunWith(classOf[JUnitRunner])
class LibpfmFormulaSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("LibpfmFormulaSpecSystem")

  "A LibpfmListener" should "listen LibpfmSensorMessage, aggregate them and send ListenerMessage" in {
    val libpfmListener = TestActorRef(new LibpfmListener with ConfigurationMock)
    val resultListener = TestActorRef(new ListenerMessageListener())
    val timeInStates = scala.collection.mutable.HashMap[Int, Long]()
    timeInStates += (1300000 -> (0L + 0L))
    timeInStates += (1200000 -> (0L + 0L))

    val msg1T1M1 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 1))
    val msg2T1M1 = LibpfmSensorMessage(counter = Counter(6768599498L), event = Event("instructions"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 1))
    val msg3T1M1 = LibpfmSensorMessage(counter = Counter(2591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(1, Process(124), 1.second), 1))
    val msg4T1M1 = LibpfmSensorMessage(counter = Counter(5768599498L), event = Event("instructions"), tick = Tick(1, TickSubscription(1, Process(124), 1.second), 1))
    val msg1T1M2 = LibpfmSensorMessage(counter = Counter(1197309162L), event = Event("cycles"), tick = Tick(2, TickSubscription(2, Process(123), 1.second), 2))

    val msg1T2M1 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 2))
    
    libpfmListener.underlyingActor.process(msg1T1M1)
    libpfmListener.underlyingActor.process(msg1T1M2)
    libpfmListener.underlyingActor.process(msg2T1M1)
    libpfmListener.underlyingActor.process(msg3T1M1)
    libpfmListener.underlyingActor.process(msg4T1M1)
    libpfmListener.underlyingActor.process(msg1T2M1)
    
    resultListener.underlyingActor.cache.keys should (contain(1) and not contain(2))
    resultListener.underlyingActor.cache(1)(0) should equal(LibpfmListenerMessage(tick = Tick(1,TickSubscription(1,Process(123),1 second),1), timeInStates = TimeInStates(timeInStates.toMap), messages = List(msg1T1M1, msg2T1M1)))
    libpfmListener.underlyingActor.cache.keys should (
      contain(Tick(1, TickSubscription(1, Process(124), 1.second), 1)) and contain(Tick(2, TickSubscription(2, Process(123), 1.second), 2)) and contain(Tick(1, TickSubscription(1, Process(123), 1.second), 2))
      and not contain(Tick(1, TickSubscription(2, Process(123), 1.second), 1))
    )
  }
  
  "A LibpfmFormula" should "be configured with the values written in a configuration file" in {
    val libpfmFormula = TestActorRef(new LibpfmFormula())
    val formulae = new scala.collection.mutable.HashMap[Long, scala.collection.mutable.ArrayBuffer[Double]]()
    formulae += (1200000L -> scala.collection.mutable.ArrayBuffer(3.975152144472587E-10,3.6663704141026026E-10,32.96899190728478))
    formulae += (1300000L -> scala.collection.mutable.ArrayBuffer(4.1257289323769395E-10,3.6174234365649696E-10,33.01644964171548))

    libpfmFormula.underlyingActor.formulae.keys should contain(1200000L)
    libpfmFormula.underlyingActor.formulae(1200000L) should equal(formulae(1200000L))
    libpfmFormula.underlyingActor.formulae.keys should contain(1300000L)
    libpfmFormula.underlyingActor.formulae(1300000L) should equal(formulae(1300000L))

    val events = Array("cycles", "instructions")
    libpfmFormula.underlyingActor.events should equal(events)
  }

  "A LibpfmFormula" should "be able to listen LibpfmSensorMessage and compute the CPU consumption" in {
    val libpfmFormula = TestActorRef(new LibpfmFormula())

    val timeInStatesI = scala.collection.mutable.HashMap[Int, Long]()
    timeInStatesI += (1300000 -> 0)
    timeInStatesI += (1200000 -> 0)

    val timeInStatesE = scala.collection.mutable.HashMap[Int, Long]()
    timeInStatesE += (1300000 -> (1105L + 914L))
    timeInStatesE += (1200000 -> (451396L + 453426L)) 

    val formulaF1 = Array(3.975152144472587E-10,3.6663704141026026E-10,32.96899190728478)
    val formulaF2 = Array(4.1257289323769395E-10, 3.6174234365649696E-10, 33.01644964171548)
    
    val timeF1 = timeInStatesE(1200000)
    val timeF2 = timeInStatesE(1300000)

    val globalTime = timeInStatesE.values.sum
    println(globalTime)
    var powerF1 = (formulaF1(0) * 3591479118L + formulaF1(1) * 6768599498L) * (timeF1.toDouble / globalTime)
    var powerF2 = (formulaF2(0) * 3591479118L + formulaF2(1) * 6768599498L) * (timeF2.toDouble / globalTime)
    val power = powerF1 + powerF2

    val listener = TestActorRef(new FormulaMessageListener())
    val msg1T1 = LibpfmSensorMessage(counter = Counter(1591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 1))
    val msg2T1 = LibpfmSensorMessage(counter = Counter(1768599498L), event = Event("instructions"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 1))

    val msg1T2 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 2))
    val msg2T2 = LibpfmSensorMessage(counter = Counter(6768599498L), event = Event("instructions"), tick = Tick(1, TickSubscription(1, Process(123), 1.second), 2))

    val msg = LibpfmListenerMessage(tick = Tick(1, TickSubscription(1, Process(123), 1.second), 1), timeInStates = TimeInStates(timeInStatesI.toMap), messages = List(msg1T1, msg2T1))
    val msg2 = LibpfmListenerMessage(tick = Tick(1, TickSubscription(1, Process(123), 1.second), 2), timeInStates = TimeInStates(timeInStatesE.toMap), messages = List(msg1T2, msg2T2))
    libpfmFormula.underlyingActor.process(msg)
    libpfmFormula.underlyingActor.process(msg2)

    listener.underlyingActor.messages should have size 2
    listener.underlyingActor.messages(0) should equal(LibpfmFormulaMessage(Tick(1,TickSubscription(1,Process(123),1 second),1),Energy.fromPower(0.0),"cpu"))
    listener.underlyingActor.messages(1) should equal(LibpfmFormulaMessage(Tick(1,TickSubscription(1,Process(123),1 second),2),Energy.fromPower(power),"cpu"))
  }
}