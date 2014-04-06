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
package fr.inria.powerapi.processor.aggregator.process

import fr.inria.powerapi.processor.aggregator.timestamp.AggregatedMessage
import fr.inria.powerapi.processor.aggregator.timestamp.TimestampAggregator
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription

import scala.collection

/**
 * Aggregates FormulaMessages by their timestamps and processes.
 *
 * @author abourdon
 */
class ProcessAggregator extends TimestampAggregator {
  def byProcesses(implicit timestamp: Long): Iterable[AggregatedMessage] = {
    val base = cache(timestamp)
    val messages = collection.mutable.ArrayBuffer.empty[AggregatedMessage]
    
    for (byMonitoring <- base.messages.groupBy(_.tick.clockid)) {
      for (byProcess <- byMonitoring._2.groupBy(_.tick.subscription.process)) {
        messages += AggregatedMessage(
          tick = Tick(byMonitoring._1, TickSubscription(byProcess._1, base.tick.subscription.duration), timestamp),
          device = "all",
          messages = byProcess._2
        )
      }
    }

    messages
  }

  override def send(implicit timestamp: Long) {
    byProcesses foreach publish
  }
}