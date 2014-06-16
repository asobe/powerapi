# PowerAPI library

Defines the API that can be use by user to interact with PowerAPI. Here there are several examples to describes PowerAPI's API:

### What is the CPU energy spent by the 123 process? Please give me fresh results every 500 milliseconds

Assume that process run under Linux, using a [procfs](http://en.wikipedia.org/wiki/Procfs "Procfs") file system on a _standard_ CPU architecture.
Thus, we need to use the _procfs_ CPU `Sensor` implementation and a given CPU `Formula` implementation, let's say the [DVFS](http://en.wikipedia.org/wiki/Voltage_and_frequency_scaling "DVFS") version. Add to this the desire to display CPU energy spent by process into a console. So we need to:

1. Choose the required modules.

2. Create the API with its dependencies:

```scala
val powerapi = new fr.inria.powerapi.library.PAPI with fr.inria.powerapi.sensor.cpu.proc.times.CpuProcTimes
                                                  with fr.inria.powerapi.formula.cpu.dvfs.FormulaCpuDVFS
                                                  with fr.inria.powerapi.processor.aggregator.timestamp.AggregatorTimestamp
```
3. Ask to PowerAPI to provide the CPU energy spent by the 123 process during 5 minutes, every 500 milliseconds, using a _console Reporter_ and aggregating results by timestamp produced every 500 milliseconds:

```scala
val monitoring = powerapi.start(500.milliseconds, fr.inria.powerapi.library.PIDS(123))
monitoring.attachReporter(fr.inria.powerapi.reporter.console.ConsoleReporter)
monitoring.waitFor(5.minutes)
powerapi.stop()
```

### Based on the first request, how can I display CPU energy information into a chart too?

Based on the previous code, we simply have to add a new `Reporter` which will be able to display CPU energy information into a chart.
PowerAPI integrates a `Reporter` using the [JFreeChart](http://www.jfree.org/jfreechart "JFreeChart") Java graph library. So let's add it to the PowerAPI system:

```scala
monitoring.attachReporter(fr.inria.powerapi.reporter.jfreechart.JFreeChartReporter)
```

That's all!