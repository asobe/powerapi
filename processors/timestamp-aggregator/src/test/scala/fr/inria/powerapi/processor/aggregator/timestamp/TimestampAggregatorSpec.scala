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
package fr.inria.powerapi.processor.aggregator.timestamp

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite, JUnitRunner}
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.core.FormulaMessage
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.core.Process

case class FormulaMessageMock(energy: Energy, tick: Tick, device: String = "mock") extends FormulaMessage

class TimestampAggregatorMock extends TimestampAggregator {
  val sent = collection.mutable.Map[Long, AggregatedMessage]()

  override def send(implicit args: List[Long]) = {
    cache(args(0)).foreach { 
      case (_, msg) => sent += args(0) -> msg
    }
  }
}

@RunWith(classOf[JUnitRunner])
class TimestampAggregatorSpec extends FlatSpec with Matchers with AssertionsForJUnit {
  implicit val system = ActorSystem("timestamp-aggregator-spec")
  val timestampAggregator = TestActorRef[TimestampAggregatorMock]

  "A TimestampAggregator" should "listen to FormulaMessage" in {
    timestampAggregator.underlyingActor.messagesToListen should equal(Array(classOf[FormulaMessage]))
  }

  "A TimestampAggregator" should "process a FormulaMessage" in {
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(1), Tick(TickSubscription(1, Process(123), 500 milliseconds), 1)))
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(2), Tick(TickSubscription(1, Process(123), 500 milliseconds), 1)))
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(3), Tick(TickSubscription(1, Process(123), 500 milliseconds), 1)))
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(4), Tick(TickSubscription(2, Process(123), 1 second), 1)))
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(5), Tick(TickSubscription(2, Process(123), 1 second), 1)))
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(6), Tick(TickSubscription(2, Process(123), 1 second), 1)))

    timestampAggregator.underlyingActor.sent should be('empty)

    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(1), Tick(TickSubscription(1, Process(123), 500 milliseconds), 2)))
    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(1), Tick(TickSubscription(2, Process(123), 1 second), 2)))

    timestampAggregator.underlyingActor.sent should have size 2
    timestampAggregator.underlyingActor.sent should contain key 1
    timestampAggregator.underlyingActor.sent(1).energy should equal(Energy.fromPower(1 + 2 + 3))
    timestampAggregator.underlyingActor.sent should contain key 2
    timestampAggregator.underlyingActor.sent(2).energy should equal(Energy.fromPower(4 + 5 + 6))

    timestampAggregator.underlyingActor.process(FormulaMessageMock(Energy.fromPower(1), Tick(TickSubscription(1, Process(123), 1 second), 3)))

    // The map is not cleared, so all the previous values are kept.
    timestampAggregator.underlyingActor.sent should have size 2
    timestampAggregator.underlyingActor.sent should contain key 1
    timestampAggregator.underlyingActor.sent(1).energy should equal(Energy.fromPower(1))
  }
}
