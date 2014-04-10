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
package fr.inria.powerapi.formula.disk.single
import org.junit.Test
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite}
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.testkit.TestActorRef

class RateSuite extends JUnitSuite with Configuration with Matchers with AssertionsForJUnit{
  val megaByte = 1000000.0
  val gigaByte = 1000000000.0

  @Test
  def testFromRateToDoubleSimpleNumber() {
    "1GB/s".fromRateToDouble should equal(1 * gigaByte)
  }

  @Test
  def testFromRateToDoubleRealNumberWithDot() {
    "2.3GB/s".fromRateToDouble should equal(2.3 * gigaByte)
  }

  @Test
  def testFromRateToDoubleRealNumberWithComma() {
    "2,3GB/s".fromRateToDouble should equal(2.3 * gigaByte)
  }

  @Test
  def testFromRateToDoubleWithMegaMultiplier() {
    "2,3MB/s".fromRateToDouble should equal(2.3 * megaByte)
  }
}