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
package fr.inria.powerapi.sensor.libpfm

import java.lang.management.ManagementFactory

import scala.concurrent.duration.DurationInt
import scala.util.Properties

import org.junit.runner.RunWith
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}
import org.scalatest.Matchers
import org.scalatest.FlatSpec

import scala.sys.process._

@RunWith(classOf[JUnitRunner])
class LibpfmUtilSpec extends FlatSpec with Matchers {
  val currentPid = ManagementFactory.getRuntimeMXBean.getName.split("@")(0).toInt

  "Init. method" should "initiliaze the libpfm" in {
    LibpfmUtil.isAlreadyInit should equal(false)
    val init = LibpfmUtil.initialize()
    LibpfmUtil.isAlreadyInit should equal(true)
    init should equal(true)
    LibpfmUtil.initialize()
    LibpfmUtil.isAlreadyInit should equal(true)
  }

  "convertBitsetToLong method" should "convert a bitset to the corresponding long" in {
    var long = 0L
    var bitset = new java.util.BitSet()
    // the bit 20 is mandatory, but the method edits it to 1 by default.
    long = LibpfmUtil.convertBitsetToLong(bitset)
    long should equal(0L)
    bitset.set(0)
    bitset.set(1)
    // Only 23 bits are allowed
    bitset.set(24)
    long = LibpfmUtil.convertBitsetToLong(bitset)
    long should equal((1L << 0) + (1L << 1))
    bitset = new java.util.BitSet()
    bitset.set(0)
    bitset.set(1)
    bitset.set(2)
    long = LibpfmUtil.convertBitsetToLong(bitset)
    long should equal((1L << 0) + (1L << 1) + (2L << 1))
  }

  "LibpfmUtil" should "allow to read a counter represented by an event (string)" in {
    val seqCmd = Seq("/bin/bash", "./src/test/resources/test.bash")
    val process = Process(seqCmd, None, "PATH" -> "/bin")
    val buffer = process.lines
    val ppid = buffer(0).trim.toInt

    val bitset = new java.util.BitSet()
    bitset.set(0)
    bitset.set(1)

    def run(fd: Int) = {
      var i = 0

      LibpfmUtil.resetCounter(fd) should equal(true)
      LibpfmUtil.enableCounter(fd) should equal(true)

      Seq("kill", "-SIGCONT", ppid+"") !

      while(i < 5) {
        Thread.sleep(1000)
        var values = LibpfmUtil.readCounter(fd)
        println("cycles: " + values(0) + "; enabled time: " + values(1) + "; running time: " + values(2))
        println("-----------------------------------")
        i += 1
      }

      Seq("pkill", "-9", "-P", ppid+"") !

      LibpfmUtil.disableCounter(fd) should equal(true)
      LibpfmUtil.closeCounter(fd) should equal(true)
      LibpfmUtil.terminate()
    }

    LibpfmUtil.initialize() should equal(true)

    LibpfmUtil.configureCounter(ppid, bitset, "cycles") match {
      case Some(fd) => run(fd)
      case None => fail("cycles is an existing counter, it should be work.")
    }
  }
}