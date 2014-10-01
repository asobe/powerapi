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
import fr.inria.powerapi.sensor.libpfm.{ Core, Counter, Event, LibpfmCoreSensorMessage }
import fr.inria.powerapi.sensor.cpu.api.TimeInStates

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.AssertionsForJUnit
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import scala.concurrent.duration.DurationInt

class Listener extends akka.actor.Actor {
  override def preStart() = {
    context.system.eventStream.subscribe(self, classOf[LibpfmAggregatedMessage])
  }

  val cache = new scala.collection.mutable.HashMap[Long, scala.collection.mutable.ArrayBuffer[LibpfmAggregatedMessage]]

  def receive() = {
    case msg: LibpfmAggregatedMessage => {
      val entry = cache.getOrElse(msg.tick.subscription.clockid, scala.collection.mutable.ArrayBuffer[LibpfmAggregatedMessage]())
      entry += msg
      cache(msg.tick.subscription.clockid) = entry
    }
    case _ => println("ooops ...")
  }
}

@RunWith(classOf[JUnitRunner])
class LibpfmCoreCyclesFormulaSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("LibpfmFormulaSpecSystem")

  "A LibpfmCoreCyclesListener" should "listen LibpfmCoreSensorMessage, aggregate them and send LibpfmAggregatedMessage" in {
    val libpfmListener = TestActorRef(new LibpfmCoreCyclesListener())
    val resultListener = TestActorRef(new Listener())
  
    val msg1T1C0M1 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(2964623970L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg1T1C1M1 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(883794728L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg1T1C0RefM1 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(135492637L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg1T1C1RefM1 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(42975065L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))

    val msg1T1C0M2 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(2964623970L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    val msg1T1C1M2 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(883794728L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    val msg1T1C0RefM2 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(135492637L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    val msg1T1C1RefM2 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(42975065L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))

    val msg1T2C0M1 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(2964623970L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 2))
    
    libpfmListener.underlyingActor.process(msg1T1C0M1)
    libpfmListener.underlyingActor.process(msg1T1C1M1)
    libpfmListener.underlyingActor.process(msg1T1C0RefM1)
    libpfmListener.underlyingActor.process(msg1T1C1RefM1)
    libpfmListener.underlyingActor.process(msg1T1C0M2)
    libpfmListener.underlyingActor.process(msg1T1C1M2)
    libpfmListener.underlyingActor.process(msg1T1C0RefM2)
    libpfmListener.underlyingActor.process(msg1T1C1RefM2)

    libpfmListener.underlyingActor.process(msg1T2C0M1)
    
    resultListener.underlyingActor.cache.keys should (contain(1) and not contain(2))
    resultListener.underlyingActor.cache(1).size should equal(1)
   
    libpfmListener.underlyingActor.cache.keys should (not contain((1, 1)) and contain(2, 1) and contain(1, 2))
  }

  "A LibpfmCoreCyclesFormula" should "be able to listen LibpfmAggregatedMessage and send the CPU consumption" in {
    val formulae = new scala.collection.mutable.HashMap[Double, Array[Double]]()
    formulae += (12d -> Array(85.7545270697,1.10006565433e-08,-2.0341944068e-18))
    formulae += (13d -> Array(87.0324917754,9.03486530986e-09,-1.31575869787e-18))
    formulae += (14d -> Array(86.3094440375,1.04895773556e-08,-1.61982669617e-18))
    formulae += (15d -> Array(88.2194900717,8.71468661777e-09,-1.12354133527e-18))
    formulae += (16d -> Array(85.8010062547,1.05239105674e-08,-1.34813984791e-18))
    formulae += (17d -> Array(85.5127064474,1.05732955159e-08,-1.28040830962e-18))
    formulae += (18d -> Array(85.5593567382,1.07921513277e-08,-1.22419197787e-18))
    formulae += (19d -> Array(87.2004521609,9.99728883739e-09,-9.9514346029e-19))
    formulae += (20d -> Array(87.7358230435,1.00553994023e-08,-1.00002335486e-18))
    formulae += (21d -> Array(94.4635683042,4.83140424765e-09,4.25218895447e-20))
    formulae += (22d -> Array(104.356371072,3.75414807806e-09,6.73289818651e-20))

    val msg1T1C0M1 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(2964623970L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg1T1C1M1 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(883794728L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg1T1C0RefM1 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(135492637L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    val msg1T1C1RefM1 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(42975065L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 1))
    var p123 = formulae(math.ceil(2964623970L/135492637L.toDouble))(1) * 2964623970L + formulae(math.ceil(2964623970L/135492637L.toDouble))(2) * math.pow(2964623970L, 2)
    p123 += formulae(math.ceil(883794728L/42975065L.toDouble))(1) * 883794728L + formulae(math.ceil(883794728L/42975065L.toDouble))(2) * math.pow(883794728L, 2)
    val coeffp123 = math.max(math.ceil(2964623970L/135492637L.toDouble), math.ceil(883794728L/42975065L.toDouble))

    val msg1T1C0M1B = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(885181499L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(124), 1.second), 1))
    val msg1T1C1M1B = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(883794728L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(124), 1.second), 1))
    val msg1T1C0RefM1B = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(42975065L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(1, Process(124), 1.second), 1))
    val msg1T1C1RefM1B = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(42975065L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(1, Process(124), 1.second), 1))
    var p124 = formulae(math.ceil(885181499L/42975065L.toDouble))(1) * 885181499L + formulae(math.ceil(885181499L/42975065L.toDouble))(2) * math.pow(885181499L, 2)
    p124 += formulae(math.ceil(883794728L/42975065L.toDouble))(1) * 883794728L + formulae(math.ceil(883794728L/42975065L.toDouble))(2) * math.pow(883794728L, 2)
    val coeffp124 = math.max(math.ceil(885181499L/42975065L.toDouble), math.ceil(883794728L/42975065L.toDouble))

    val msg1T1C0M2 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(2964623970L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    val msg1T1C1M2 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(883794728L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    val msg1T1C0RefM2 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(135492637L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    val msg1T1C1RefM2 = LibpfmCoreSensorMessage(core = Core(1), counter = Counter(42975065L), event = Event("CPU_CLK_UNHALTED:REF_P"), tick = Tick(TickSubscription(2, Process(124), 1.second), 1))
    
    val msg1T2C0M1 = LibpfmCoreSensorMessage(core = Core(0), counter = Counter(2964623970L), event = Event("CPU_CLK_UNHALTED:THREAD_P"), tick = Tick(TickSubscription(1, Process(123), 1.second), 2))
    
    val libpfmListener = TestActorRef(new LibpfmCoreCyclesListener())
    val resultListener = TestActorRef(new Listener())

    val formula = TestActorRef(new LibpfmCoreCyclesFormula())
    val formulaListener = TestActorRef(new FormulaMessageListener())

    libpfmListener.underlyingActor.process(msg1T1C0M1)
    libpfmListener.underlyingActor.process(msg1T1C1M1)
    libpfmListener.underlyingActor.process(msg1T1C0RefM1)
    libpfmListener.underlyingActor.process(msg1T1C1RefM1)
    libpfmListener.underlyingActor.process(msg1T1C0M1B)
    libpfmListener.underlyingActor.process(msg1T1C1M1B)
    libpfmListener.underlyingActor.process(msg1T1C0RefM1B)
    libpfmListener.underlyingActor.process(msg1T1C1RefM1B)
    libpfmListener.underlyingActor.process(msg1T1C0M2)
    libpfmListener.underlyingActor.process(msg1T1C1M2)
    libpfmListener.underlyingActor.process(msg1T1C0RefM2)
    libpfmListener.underlyingActor.process(msg1T1C1RefM2)

    libpfmListener.underlyingActor.process(msg1T2C0M1)
    resultListener.underlyingActor.cache.size should equal(1)
    resultListener.underlyingActor.cache(1).foreach(msg => formula.underlyingActor.process(msg))
    formulaListener.underlyingActor.messages should have size 2
    formulaListener.underlyingActor.messages should contain(LibpfmCoreCyclesFormulaMessage(tick = Tick(TickSubscription(1,Process(124),1 second),1), energy = Energy.fromPower(p124), maxCoefficient = coeffp124, device = "cpu"))
    formulaListener.underlyingActor.messages should contain(LibpfmCoreCyclesFormulaMessage(tick = Tick(TickSubscription(1,Process(123),1 second),1), energy = Energy.fromPower(p123), maxCoefficient = coeffp123, device = "cpu"))
  }
}
