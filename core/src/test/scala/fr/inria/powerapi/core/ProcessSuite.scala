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

import org.junit.Test
import org.scalatest.junit.{AssertionsForJUnit, JUnitSuite}
import org.scalatest.Matchers

trait MockThreadsConfiguration extends ThreadsConfiguration {
  override lazy val taskPath = "./src/test/resources/proc/$pid/task"
}

class ProcessMock(pid: Int) extends Process(pid) with MockThreadsConfiguration

class ProcessSuite extends JUnitSuite with Matchers with AssertionsForJUnit {

  @Test
  def testThreadsMethod {
    new ProcessMock(1).threads should contain theSameElementsAs Array(2, 3)
    new ProcessMock(4).threads shouldBe empty
    new ProcessMock(-1).threads shouldBe empty
  }
}