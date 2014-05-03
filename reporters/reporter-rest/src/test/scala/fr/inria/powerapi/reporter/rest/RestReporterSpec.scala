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

import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}

import akka.testkit.TestActorRef

import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest
import StatusCodes._

import org.junit.runner.RunWith
import org.scalatest.FunSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.junit.ShouldMatchersForJUnit

import fr.inria.powerapi.core.Process
import fr.inria.powerapi.formula.cpu.reg.FormulaCpuReg
import fr.inria.powerapi.library.{ PAPI, PIDS }
import fr.inria.powerapi.processor.aggregator.process.AggregatorProcess
import fr.inria.powerapi.sensor.cpu.proc.reg.SensorCpuProcReg

@RunWith(classOf[JUnitRunner])
class RestReporterSpec extends FunSpec with ShouldMatchersForJUnit with ScalatestRouteTest with RestService {
  def actorRefFactory = system
  import RestServiceJsonProtocol._
  
  
  val powerapi = new PAPI with SensorCpuProcReg with FormulaCpuReg with AggregatorProcess
  val monitoring = powerapi.start(PIDS(), 1.seconds)
  def mon() = monitoring
  
  describe("The RestService") {

    it("return a empty PIDs list for GET requests to /energy in begining") {
      Get("/energy") ~> rest ~> check { responseAs[PidList].list should have size 0 }
    }
    
    it("return a '1234 started' response for POST requests to /energy/1234/start") {
      Post("/energy/1234/start") ~> rest ~> check { responseAs[String] should equal ("1234 started") }
    }
    
    it("return a non-empty PIDs list for GET requests to /energy after starting the monitoring of one process") {
      Get("/energy") ~> rest ~> check { responseAs[PidList].list should contain (1234) }
    }
    
    it("return a '5678 started' response for POST requests to /energy/5678/start") {
      Post("/energy/5678/start") ~> rest ~> check { responseAs[String] should equal ("5678 started") }
    }
    
    it("return a non-empty PIDs list for GET requests to /energy after starting the monitoring of two processes") {
      Get("/energy") ~> rest ~> check {
        responseAs[PidList].list should contain (1234)
        responseAs[PidList].list should contain (5678)
      }
    }
    
    it("return a '1234 stopped' response for POST requests to /energy/1234/stop") {
      Post("/energy/1234/stop") ~> rest ~> check { responseAs[String] should equal ("1234 stopped") }
    }
    
    it("return a '5678 stopped' response for POST requests to /energy/5678/stop") {
      Post("/energy/5678/stop") ~> rest ~> check { responseAs[String] should equal ("5678 stopped") }
    }

    it("return a empty PIDs list for GET requests to /energy after stopping the monitoring of the process") {
      Get("/energy") ~> rest ~> check { responseAs[PidList].list should have size 0 }
    }

    it("leave GET requests to other paths unhandled") {
      Get("/kermit") ~> rest ~> check { handled should equal (false) }
    }
    
    it("leave POST requests to other paths unhandled") {
      Post("/kermit") ~> rest ~> check { handled should equal (false) }
    }

    it("return a MethodNotAllowed error for POST requests to /energy") {
      Post("/energy") ~> sealRoute(rest) ~> check {
        status should equal (MethodNotAllowed)
        responseAs[String] should equal ("HTTP method not allowed, supported methods: GET")
      }
    }

    it("return a MethodNotAllowed error for PUT requests to /energy") {
      Put("/energy") ~> sealRoute(rest) ~> check {
        status should equal (MethodNotAllowed)
        responseAs[String] should equal ("HTTP method not allowed, supported methods: GET")
      }
    }
    
    it("return a MethodNotAllowed error for DELETE requests to /energy") {
      Delete("/energy") ~> sealRoute(rest) ~> check {
        status should equal (MethodNotAllowed)
        responseAs[String] should equal ("HTTP method not allowed, supported methods: GET")
      }
    }
  }
  
  //powerapi.stop
}
