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
package fr.inria.powerapi.core

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.Test
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite}
import org.scalatest.Matchers

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout

case class Create[U <: APIComponent](companion: U)
object Ack

class TestActorAPI extends Component {
  var created = 0

  def messagesToListen = Array()

  def acquire = {
    case Create(companion) => {
      val actorRet = companion.apply(self)
      actorRet match {
        case Some(actorRef) => created +=1
        case None => None 
      }

      sender ! Ack
    }
    case Result => {
      sender ! created
    }
  }
}

class ComponentSuite extends JUnitSuite with Matchers with AssertionsForJUnit {
  @Test
  def testSingletonComponent() {
    object TestAPISingleton extends APIComponent {
      lazy val singleton = true
      lazy val underlyingClass = classOf[SimpleActor]
    }

    implicit val timeout = Timeout(5.seconds)
    val system = ActorSystem("APIComponentTest")
    val testActor = system.actorOf(Props[TestActorAPI])
    Await.result(testActor ? Create(TestAPISingleton), timeout.duration)
    Await.result(testActor ? Create(TestAPISingleton), timeout.duration)

    val created = Await.result(testActor ? Result, timeout.duration).asInstanceOf[Int]

    TestAPISingleton.apisRegistered should have length 1
    created should equal(1)
  }

  @Test
  def testSeveralComponent() {
    object TestAPI extends APIComponent {
      lazy val singleton = false
      lazy val underlyingClass = classOf[SimpleActor]
    }

    implicit val timeout = Timeout(5.seconds)
    val system = ActorSystem("APIComponentTest")
    val testActor = system.actorOf(Props[TestActorAPI])
    Await.result(testActor ? Create(TestAPI), timeout.duration)
    Await.result(testActor ? Create(TestAPI), timeout.duration)

    val created = Await.result(testActor ? Result, timeout.duration).asInstanceOf[Int]

    TestAPI.apisRegistered should have length 1
    created should equal(2)
  }

  @Test
  def testComponentTwoAPI() {
    object TestAPISingleton extends APIComponent {
      lazy val singleton = true
      lazy val underlyingClass = classOf[SimpleActor]
    }

    object TestAPI extends APIComponent {
      lazy val singleton = false
      lazy val underlyingClass = classOf[SimpleActor]
    }

    implicit val timeout = Timeout(5.seconds)
    val system = ActorSystem("APIComponentTest")
    
    val testActorOne = system.actorOf(Props[TestActorAPI])
    Await.result(testActorOne ? Create(TestAPISingleton), timeout.duration)
    Await.result(testActorOne ? Create(TestAPISingleton), timeout.duration)
    Await.result(testActorOne ? Create(TestAPI), timeout.duration)

    val testActorTwo = system.actorOf(Props[TestActorAPI])
    Await.result(testActorTwo ? Create(TestAPISingleton), timeout.duration)

    val createdActorsOne = Await.result(testActorOne ? Result, timeout.duration).asInstanceOf[Int]
    val createdActorsTwo = Await.result(testActorTwo ? Result, timeout.duration).asInstanceOf[Int]

    TestAPISingleton.apisRegistered should have length 2
    TestAPI.apisRegistered should have length 1

    createdActorsOne should equal(2)
    createdActorsTwo should equal(1)
  }
}