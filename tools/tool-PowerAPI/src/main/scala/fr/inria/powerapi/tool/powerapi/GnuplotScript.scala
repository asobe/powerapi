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
package fr.inria.powerapi.tool.powerapi

import scalax.file.Path
import scalax.io.Resource

/**
 * Create the gnuplot script which have to generate the graph.
 *
 * @author lhuertas
 */
object GnuplotScript {
  def create(elems: List[Int], filePath: String) {
      lazy val outputPlot = {
        Path.fromString(filePath+".plot").deleteIfExists()
        Resource.fromFile(filePath+".plot")
      }
      outputPlot.append("set terminal pngcairo size 1024,768 enhanced font 'Verdana,10'" + scalax.io.Line.Terminators.NewLine.sep)
      outputPlot.append("set output '"+filePath+".png'" + scalax.io.Line.Terminators.NewLine.sep)
      outputPlot.append("set title 'PowerAPI'" + scalax.io.Line.Terminators.NewLine.sep)
      outputPlot.append("set xlabel 'Time (s.)'" + scalax.io.Line.Terminators.NewLine.sep)
      outputPlot.append("set ylabel 'Energy consumption (W)'" + scalax.io.Line.Terminators.NewLine.sep)
      if (elems.size > 1) {
        outputPlot.append("plot '" + filePath+".dat" + "' u 1:2 title '" + elems(0) + "' w l")
        for (i <- 3 to elems.size+1)
          outputPlot.append(", '" + filePath+".dat" + "' u 1:" + i + " title '" + elems(i-2) + "' w l")
      }
      else
        outputPlot.append("plot '" + filePath+".dat" + "' u 1:2 title 'all' w l")
      Runtime.getRuntime.exec(Array("gnuplot",filePath+".plot"))
  }
}
