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
package fr.inria.powerapi.reporter.rest
 
import akka.actor.{ Actor, ActorLogging, Props }
import akka.event.LoggingReceive
import akka.io.IO

import spray.can.Http 
import spray.client.pipelining._
import spray.http.{ HttpHeaders, MediaTypes }
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol
import spray.routing._

import com.github.nscala_time.time.Imports._

import fr.inria.powerapi.core.{ Process, ProcessedMessage, Reporter }
import fr.inria.powerapi.library.{ PAPI, Monitoring }


case class Data(host: String, pid: Int, energy: Double)
case class Event(`type`: String, time: String, data: Data)
case class PidList(list: List[Int])

object RestServiceJsonProtocol extends DefaultJsonProtocol {
  implicit val dataFormat = jsonFormat3(Data)
  implicit val eventFormat = jsonFormat3(Event)
  implicit val pidListFormat = jsonFormat1(PidList)
}

trait Configuration extends fr.inria.powerapi.core.Configuration {
  /** Host IP address used for HTTP server and for identifying a PowerAPI instance on distributed context. */
  lazy val hostAddress = load { _.getString("powerapi.reporter.rest.host.address") }("localhost")
  /** Cube database IP address. */
  lazy val cubeAddress = load { _.getString("powerapi.reporter.rest.cube.address") }("localhost")
}

/**
 * REST Reporter
 */
class RestReporter(powerapi: PAPI, monitoring: Monitoring) extends Reporter with Configuration {
  def actorRefFactory = context
  implicit def executionContext = actorRefFactory.dispatcher
  import RestServiceJsonProtocol._
  
  override def preStart() {
    context.actorOf(Props(classOf[RestActor], powerapi, monitoring), name = "REST")
  }
  
  def process(processedMessage: ProcessedMessage) {
    val pid = processedMessage.tick.subscription.process.pid
    val req = Post("http://"+cubeAddress+":1080/1.0/event/put",
                   List[Event](Event("request",
                                     DateTime.now.toString,
                                     Data(hostAddress, pid, processedMessage.energy.power))))
    val pipeline = sendReceive
    pipeline(req)
  }
}

/**
 * REST Service actor
 */
class RestActor(powerapi: PAPI, monitoring: Monitoring) extends Actor with ActorLogging with RestService with Configuration {
  def actorRefFactory = context
  
  override def preStart() {
    // start HTTP server with rest service actor as a handler
    IO(Http)(powerapi.system) ! Http.Bind(self, hostAddress, 8080)
  }
  
  def receive = LoggingReceive {
    runRoute(rest)
  }
  
  def mon() = monitoring
}

//REST Service
trait RestService extends HttpService {
  implicit def executionContext = actorRefFactory.dispatcher
  import RestServiceJsonProtocol._
  def mon(): Monitoring
  
  val rest = respondWithHeaders(HttpHeaders.`Access-Control-Allow-Origin`(spray.http.AllOrigins)) {
    path("energy") {
      get {
        respondWithMediaType(MediaTypes.`application/json`) {
          complete {
            PidList(mon().getMonitoredProcesses.map(_.pid))
          }
        }
      }
    } ~
    path("energy" / IntNumber / "start") {
      pid =>
        post {
          complete {
            mon().attachProcess(Process(pid))
            s"${pid} started"
          }
        }
    } ~
    path("energy" / IntNumber / "stop") {
      pid =>
        post {
          complete {
            mon().detachProcess(Process(pid))
            s"${pid} stopped"
          }
      }
    }
  }
}
