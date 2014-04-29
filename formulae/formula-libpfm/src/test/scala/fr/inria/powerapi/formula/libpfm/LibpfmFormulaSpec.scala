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

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.AssertionsForJUnit
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.actor.ActorSystem
import akka.testkit.TestActorRef

@RunWith(classOf[JUnitRunner])
class LibpfmFormulaSpec extends FlatSpec with Matchers with AssertionsForJUnit {

  class Listener extends akka.actor.Actor {
    override def preStart() = {
      context.system.eventStream.subscribe(self, classOf[LibpfmFormulaMessage])
    }

    val monitoringBuffers = new scala.collection.mutable.HashMap[Long, scala.collection.mutable.ArrayBuffer[LibpfmFormulaMessage]]

    def receive() = {
      case libpfmFormulaMessage: LibpfmFormulaMessage => {
        val monitoringBuffer = monitoringBuffers.getOrElse(libpfmFormulaMessage.tick.clockid, scala.collection.mutable.ArrayBuffer[LibpfmFormulaMessage]())
        monitoringBuffer += libpfmFormulaMessage
        monitoringBuffers(libpfmFormulaMessage.tick.clockid) = monitoringBuffer
      }
      case _ => println("ooops ...")
    }
  }

  implicit val system = ActorSystem("LibpfmFormulaSpecSystem")
  val libpfmFormula = TestActorRef(new LibpfmFormula())

  "A LibpfmFormula" should "be configured with the values written in a configuration file" in {
    val formulae = new scala.collection.mutable.HashMap[Long, scala.collection.mutable.ArrayBuffer[Double]]()
    formulae += (1200000L -> scala.collection.mutable.ArrayBuffer(3.975152144472587E-10,3.6663704141026026E-10,32.96899190728478))
    formulae += (1300000L -> scala.collection.mutable.ArrayBuffer(4.1257289323769395E-10,3.6174234365649696E-10,33.01644964171548))

    libpfmFormula.underlyingActor.formulae.keys should contain(1200000L)
    libpfmFormula.underlyingActor.formulae(1200000L) should equal(formulae(1200000L))
    libpfmFormula.underlyingActor.formulae.keys should contain(1300000L)
    libpfmFormula.underlyingActor.formulae(1300000L) should equal(formulae(1300000L))

    libpfmFormula.underlyingActor.idlePower should equal(29.696251268414997)

    val events = Array("cycles", "instructions")
    libpfmFormula.underlyingActor.events should equal(events)
  }

  "A LibpfmFormula" should "be able to listen LibpfmSensorMessage and compute the CPU consumption" in {
    val formula = Array(4.1257289323769395E-10, 3.6174234365649696E-10, 33.01644964171548)
    val idlePower = 29.696251268414997
    var powerM1 = formula(0) * 3591479118L + formula(1) * 6768599498L
    powerM1 += formula(0) * 2591479118L + formula(1) * 5768599498L
    powerM1 += 33.01644964171548
    powerM1 -= idlePower
    var powerM2 = formula(0) * 1197309162L + formula(1) * 3410238673L
    powerM2 += 33.01644964171548
    powerM2 -= idlePower
    var powerM3 = formula(0) * 123987456L
    powerM3 += 33.01644964171548
    powerM3 -= idlePower

    val listener = TestActorRef(new Listener())
    val msg1T1M1 = LibpfmSensorMessage(counter = Counter(3591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(Process(123), 1.second), 1))
    val msg2T1M1 = LibpfmSensorMessage(counter = Counter(6768599498L), event = Event("instructions"), tick = Tick(1, TickSubscription(Process(123), 1.second), 1))
    val msg3T1M1 = LibpfmSensorMessage(counter = Counter(2591479118L), event = Event("cycles"), tick = Tick(1, TickSubscription(Process(124), 1.second), 1))
    val msg4T1M1 = LibpfmSensorMessage(counter = Counter(5768599498L), event = Event("instructions"), tick = Tick(1, TickSubscription(Process(124), 1.second), 1))
    
    val msg1T1M2 = LibpfmSensorMessage(counter = Counter(1197309162L), event = Event("cycles"), tick = Tick(2, TickSubscription(Process(125), 1.second), 1))
    val msg2T1M2 = LibpfmSensorMessage(counter = Counter(3410238673L), event = Event("instructions"), tick = Tick(2, TickSubscription(Process(125), 1.second), 1))

    val msgT2 = LibpfmSensorMessage(counter = Counter(123987456L), event = Event("cycles"), tick = Tick(2, TickSubscription(Process(123), 1.second), 2))
    val msgT3 = LibpfmSensorMessage(counter = Counter(987123654L), event = Event("cycles"), tick = Tick(1, TickSubscription(Process(123), 1.second), 3))
    
    libpfmFormula.underlyingActor.process(msg1T1M1)
    libpfmFormula.underlyingActor.process(msg1T1M2)
    libpfmFormula.underlyingActor.process(msg2T1M2)
    libpfmFormula.underlyingActor.process(msg2T1M1)
    libpfmFormula.underlyingActor.process(msg3T1M1)
    libpfmFormula.underlyingActor.process(msg4T1M1)

    libpfmFormula.underlyingActor.process(msgT2)
    libpfmFormula.underlyingActor.process(msgT3)

    listener.underlyingActor.monitoringBuffers should have size 2
    listener.underlyingActor.monitoringBuffers.keys should contain(1)
    listener.underlyingActor.monitoringBuffers.keys should contain(2)

    listener.underlyingActor.monitoringBuffers(1) should have size 1
    listener.underlyingActor.monitoringBuffers(1)(0).energy should equal(Energy.fromPower(powerM1))
    listener.underlyingActor.monitoringBuffers(2) should have size 2
    listener.underlyingActor.monitoringBuffers(2)(0).energy should equal(Energy.fromPower(powerM2))
    listener.underlyingActor.monitoringBuffers(2)(1).energy should equal(Energy.fromPower(powerM3))
  }
}