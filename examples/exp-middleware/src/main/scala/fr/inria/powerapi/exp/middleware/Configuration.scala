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
package fr.inria.powerapi.exp.middleware

import scala.collection.JavaConversions
import scala.collection.JavaConversions._

import com.typesafe.config.Config


trait SpecConfiguration extends fr.inria.powerapi.core.Configuration {
  lazy val specpath = load { _.getString("powerapi.spec.path") }("")
}

trait ParsecConfiguration extends fr.inria.powerapi.core.Configuration {
  lazy val parsecpath = load { _.getString("powerapi.parsec.path") }("")
}

trait FormulaeConfiguration extends fr.inria.powerapi.core.Configuration {
  lazy val formulae = load {
    conf =>
      (for (item <- JavaConversions.asScalaBuffer(conf.getConfigList("powerapi.libpfm.unhalted-cycles-formulae")))
        yield (item.asInstanceOf[Config].getDouble("coefficient"), JavaConversions.asScalaBuffer(item.asInstanceOf[Config].getDoubleList("formula").map(_.toDouble)).toArray)).toMap[Double, Array[Double]]
  } (Map[Double, Array[Double]]())
}
