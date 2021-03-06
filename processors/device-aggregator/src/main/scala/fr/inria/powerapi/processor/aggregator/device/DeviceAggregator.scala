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
package fr.inria.powerapi.processor.aggregator.device

import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator
import fr.inria.powerapi.processor.aggregator.timestamp.AggregatedMessage
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.core.Process

/**
 * Aggregates FormulaMessages by their clockids (which represent the monitorings), timestamps and devices.
 *
 * By default, DeviceAggregator builds new AggregatedMessage with process = Process(-1).
 * Note that Process(-1) means "all processes".
 */
class DeviceAggregator extends TimestampAggregator {
  // clockid, timestamp
  def byDevices(implicit args: List[Long]): Iterable[AggregatedMessage] = {
    val base = cache(args(0))(args(1))

    for (byDevice <- base.messages.groupBy(_.device)) yield (AggregatedMessage(
      tick = Tick(TickSubscription(args(0), Process(-1), duration = base.tick.subscription.duration), args(1)),
      device = byDevice._1,
      messages = byDevice._2)
    )
  }

  override def send(implicit args: List[Long]) {
    byDevices foreach publish
  }
}

/**
 * Companion object used to create this given component.
 */
object AggregatorDevice extends fr.inria.powerapi.core.APIComponent {
  lazy val singleton = true
  lazy val underlyingClass = classOf[DeviceAggregator]
}

/**
 * Use to cook the bake.
 */
trait AggregatorDevice {
  self: fr.inria.powerapi.core.API =>
  configure(AggregatorDevice)
}
