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

@RunWith(classOf[JUnitRunner])
class LibpfmFormulaSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("LibpfmFormulaSpecSystem")
  
  class Listener extends akka.actor.Actor {
    override def preStart() = {
      context.system.eventStream.subscribe(self, classOf[LibpfmListenerMessage])
    }

    val cache = new scala.collection.mutable.HashMap[Long, scala.collection.mutable.ArrayBuffer[LibpfmListenerMessage]]

    def receive() = {
      case libpfmListenerMessage: LibpfmListenerMessage => {
        val entry = cache.getOrElse(libpfmListenerMessage.tick.subscription.clockid, scala.collection.mutable.ArrayBuffer[LibpfmListenerMessage]())
        entry += libpfmListenerMessage
        cache(libpfmListenerMessage.tick.subscription.clockid) = entry
      }
      case _ => println("ooops ...")
    }
  }

  "A LibpfmListener" should "listen LibpfmSensorMessage, aggregate them and send ListenerMessage" in {
    val libpfmListener = TestActorRef(new LibpfmListener())
    val resultListener = TestActorRef(new Listener())
    val timeInStates = scala.collection.mutable.HashMap[Int, Long]()
    timeInStates += (1300000 -> (0L + 0L))
    timeInStates += (1200000 -> (0L + 0L))

    val msg1T1M1 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg2T1M1 = LibpfmSensorMessage(counter = Counter(6768599498L), event = Event("instructions"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg3T1M1 = LibpfmSensorMessage(counter = Counter(2591479118L), event = Event("cycles"), tick = Tick(TickSubscription(1, Process(124), 1.second), 1))
    val msg4T1M1 = LibpfmSensorMessage(counter = Counter(5768599498L), event = Event("instructions"), tick = Tick(TickSubscription(1, Process(124), 1.second), 1))
    val msg1T1M2 = LibpfmSensorMessage(counter = Counter(1197309162L), event = Event("cycles"), tick = Tick(TickSubscription(2, Process(123), 1.second), 2))

    val msg1T2M1 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(TickSubscription(1, Process(123), 1.second), 2))
    
    libpfmListener.underlyingActor.process(msg1T1M1)
    libpfmListener.underlyingActor.process(msg1T1M2)
    libpfmListener.underlyingActor.process(msg2T1M1)
    libpfmListener.underlyingActor.process(msg3T1M1)
    libpfmListener.underlyingActor.process(msg4T1M1)
    libpfmListener.underlyingActor.process(msg1T2M1)
    
    resultListener.underlyingActor.cache.keys should (contain(1) and not contain(2))
    resultListener.underlyingActor.cache(1).size should equal(1)
    resultListener.underlyingActor.cache(1)(0) should equal(LibpfmListenerMessage(tick = Tick(TickSubscription(1,Process(123),1 second),1), timeInStates = TimeInStates(timeInStates.toMap), messages = List(msg1T1M1, msg2T1M1)))
    libpfmListener.underlyingActor.cache.keys should (
      contain(Tick(TickSubscription(1, Process(124), 1.second), 1)) and contain(Tick(TickSubscription(2, Process(123), 1.second), 2)) and contain(Tick(TickSubscription(1, Process(123), 1.second), 2))
      and not contain(Tick(TickSubscription(1, Process(123), 1.second), 1))
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
    val libpfmFormulaNoCpuFreq = TestActorRef(new LibpfmFormula() {
      override lazy val dvfs = false
    })

    val timeInStatesI = scala.collection.mutable.HashMap[Int, Long]()
    timeInStatesI += (1300000 -> 0) // F2
    timeInStatesI += (1200000 -> 0) // F1

    val timeInStatesAllFreqEnabled = scala.collection.mutable.HashMap[Int, Long]()
    timeInStatesAllFreqEnabled += (1300000 -> (1105L + 914L)) // F2
    timeInStatesAllFreqEnabled += (1200000 -> (451396L + 453426L)) // F1

    val timeInStatesMinFreqFixed = scala.collection.mutable.HashMap[Int, Long]()
    timeInStatesMinFreqFixed += (1300000 -> (0L + 0)) // F2
    timeInStatesMinFreqFixed += (1200000 -> (11119105L + 9233214L)) // F1

    val formulaF1 = Array(3.975152144472587E-10,3.6663704141026026E-10,32.96899190728478)
    val formulaF2 = Array(4.1257289323769395E-10, 3.6174234365649696E-10, 33.01644964171548)
  
    val powerAllFreqEnabled = (formulaF2(0) * 3591479118L + formulaF2(1) * 6768599498L) * (timeInStatesAllFreqEnabled(1300000).toDouble / ((timeInStatesAllFreqEnabled - timeInStatesAllFreqEnabled.keys.min).values.sum))
    val powerNoCpuFreq = formulaF2(0) * 3591479118L + formulaF2(1) * 6768599498L
    val powerMinFreqFixed = (formulaF1(0) * 3591479118L + formulaF1(1) * 6768599498L) * (timeInStatesMinFreqFixed(1200000).toDouble / timeInStatesMinFreqFixed.values.sum)

    val listener = TestActorRef(new FormulaMessageListener())
    val msg1T1 = LibpfmSensorMessage(counter = Counter(1591479118L), event = Event("cycles"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg2T1 = LibpfmSensorMessage(counter = Counter(1768599498L), event = Event("instructions"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))

    val msg1T2 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(TickSubscription(1, Process(123), 1.second), 2))
    val msg2T2 = LibpfmSensorMessage(counter = Counter(6768599498L), event = Event("instructions"), tick = Tick(TickSubscription(1, Process(123), 1.second), 2))

    val msg = LibpfmListenerMessage(tick = Tick(TickSubscription(1, Process(123), 1.second), 1), timeInStates = TimeInStates(timeInStatesI.toMap), messages = List(msg1T1, msg2T1))
    val msg2 = LibpfmListenerMessage(tick = Tick(TickSubscription(1, Process(123), 1.second), 2), timeInStates = TimeInStates(timeInStatesAllFreqEnabled.toMap), messages = List(msg1T2, msg2T2))
    val msg2B = LibpfmListenerMessage(tick = Tick(TickSubscription(1, Process(123), 1.second), 2), timeInStates = TimeInStates(timeInStatesMinFreqFixed.toMap), messages = List(msg1T2, msg2T2))

    libpfmFormula.underlyingActor.process(msg)
    libpfmFormula.underlyingActor.process(msg2)
    libpfmFormula.underlyingActor.process(msg2B)
    libpfmFormulaNoCpuFreq.underlyingActor.process(msg)
    libpfmFormulaNoCpuFreq.underlyingActor.process(msg2)


    listener.underlyingActor.messages should have size 4
    listener.underlyingActor.messages should contain(LibpfmFormulaMessage(Tick(TickSubscription(1,Process(123),1 second),1),Energy.fromPower(0.0),"cpu"))
    listener.underlyingActor.messages should contain(LibpfmFormulaMessage(Tick(TickSubscription(1,Process(123),1 second),2),Energy.fromPower(powerAllFreqEnabled),"cpu"))
    listener.underlyingActor.messages should contain(LibpfmFormulaMessage(Tick(TickSubscription(1,Process(123),1 second),2),Energy.fromPower(powerMinFreqFixed),"cpu"))
    listener.underlyingActor.messages should contain(LibpfmFormulaMessage(Tick(TickSubscription(1,Process(123),1 second),2),Energy.fromPower(powerNoCpuFreq),"cpu"))
  }
}
