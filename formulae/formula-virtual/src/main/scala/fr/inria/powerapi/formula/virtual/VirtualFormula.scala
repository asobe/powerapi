package fr.inria.powerapi.formula.virtual

import fr.inria.powerapi.core.Formula
import fr.inria.powerapi.core.FormulaMessage
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.Energy
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage
import fr.inria.powerapi.formula.cpu.api.CpuFormulaMessage

//case class VirtualFormulaMessage(information:String,energy:Energy,tick:Tick,device:String="virtual") extends FormulaMessage

class VirtualFormula extends fr.inria.powerapi.formula.cpu.api.CpuFormula {
//def messagesToListen = Array(classOf[CpuSensorMessage])

  def compute(now: CpuSensorMessage) = {
    val CPUpower = -6.7476468907802865 + (133.27549174479248 * now.processPercent.percent) - (147.00068570951598 * math.pow(now.processPercent.percent,2)) + (55.2647624073449 * math.pow(now.processPercent.percent,3))

    if (now.activityPercent.percent == 0)
      Energy.fromPower(0)
    else
      Energy.fromPower(((now.processPercent.percent * CPUpower).doubleValue()) / now.activityPercent.percent)
  }

  def process(cpuSensorMessage: CpuSensorMessage) {
    publish(CpuFormulaMessage(compute(cpuSensorMessage), cpuSensorMessage.tick))
  }

}
