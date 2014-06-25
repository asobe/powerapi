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
package fr.inria.powerapi.library

object Util {
  /**
   * This method is responsible to add a resource into the classpath during the run.
   */
  def addResourceToClasspath(classpath: String): Boolean = {
    try {
      val file = new java.io.File(classpath)
      if(!file.exists()) return false
      
      val pathname = file.toURI()
      val urlClassLoader = ClassLoader.getSystemClassLoader()
      val urlClass = classOf[java.net.URLClassLoader]
      val method = urlClass.getDeclaredMethod("addURL", classOf[java.net.URL])
      method.setAccessible(true)
      method.invoke(urlClassLoader, pathname.toURL())
      true
    }
    catch {
      case e: Exception => false
    }
  }

  /**
   * This method allows to convert properly a string to a long.
   */
  def stringToLong(str: String) = {
    try {
      Some(str.toLong)
    }
    catch {
      case e: java.lang.NumberFormatException => None
    }
  }
}