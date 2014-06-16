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

import java.io.File
import scala.sys.process._

trait ThreadsConfiguration extends Configuration {
  /** Path to the directory in procfs which is used to get all the associated threads. */
  lazy val taskPath = load { _.getString("powerapi.pid.task") }("/proc/$pid/task")
}

/**
 * System process wrapper.
 *
 * @param pid: the associated Process identifier.
 */
case class Process(pid: Int) extends ThreadsConfiguration {
  // Allows to get the associated threads for a PID.
  def threads = {
    val entry = new File(taskPath.replace("$pid", pid.toString))
    val threadSet = scala.collection.mutable.Set[Int]()

    // Test whether the pid exists or not. When true, get all the associated threads.
    if(entry.isDirectory()) {
      threadSet ++= (for(tid <- scala.sys.process.Process("ls", new File(taskPath.replace("$pid", pid.toString))).lines.toArray) yield tid.trim.toInt)
    }

    // The main thread is removed because of it corresponds to the PID (main thread).
    (threadSet - pid).toSet
  }
}