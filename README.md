# PowerAPI

PowerAPI is a [Scala-based library](http://www.scala-lang.org "Scala programming language") for monitoring energy at the process-level. It is based on a modular and asynchronous event-driven architecture using the [Akka library](http://akka.io "Akka actor library").

PowerAPI differs from existing energy process-level monitoring tool in its software orientation, with a fully customizable and modular solution that let the user to precisely define what he/she wants to monitor.

PowerAPI offers an API which can be used to define request about energy spent by a process, following its hardware resource utilization (in term of CPU, memory, disk, network, etc.).

## Documentation
* [Getting started](#getting-started)
* [Architecture details](#architecture-details)
* [API details](#api-details)
* [Future works](#future-works)
* [License](#license)

<h2 id="getting-started">Getting started</h2>

PowerAPI is fully written in [Scala](http://www.scala-lang.org "Scala language") (v. 2.9.1+), using the [Akka library](http://akka.io "Akka library") (v 2.0.1+). Configuration part is managed by the [Typesafe Config](https://github.com/typesafehub/config "Typesafe Config") (integrated version from the [Akka library](http://akka.io "Akka library")).
PowerAPI project is fully managed by [Maven](http://maven.apache.org "Maven") (v. 3).

### How to install it

There are two ways to install PowerAPI: with or without Maven repositories. In other words, directly from [Maven](http://maven.apache.org) repositories (to download stable or snapshot versions), or from our [Git](http://git-scm.com) repository (to download the source code).

#### Maven repositories (binaries)

**Stable versions** are available from the [Maven central repository](http://search.maven.org "Maven central repository"). Thus, you just have to put on your `pom.xml` file your desired modules.

**Snapshot versions** are available from the [OSS Sonatype repository](https://oss.sonatype.org "OSS Sonatype repository"). Thus, you have to add this following repository location:

```xml
<repository>
    <id>OSS Sonatype snapshot repository</id>
	<url>https://oss.sonatype.org/content/repositories/snapshots</url>
	<snapshots>
		<enabled>true</enabled>
	</snapshots>
</repository>
```

After that, you just have to put on your `pom.xml` file your desired modules.

#### Git repository (source)

Without Maven repositories, you have to deal with our Git repository as explain bellow:

To acquire PowerAPI, simply clone it via your Git client:

```bash
git clone git://github.com/rouvoy/powerapi.git
```

As PowerAPI is a [Maven](http://maven.apache.org "Maven") managed project, you have to launch the `install` command at the root directory (here, `POWERAPI_DIR`) in order to compile and install it to your local machine:

```bash
cd $POWERAPI_DIR
mvn install
```

**By default, all modules are selected to be installed. Be careful to correctly selecting yours, depending on your environment and the use case you want to do** (see `pom.xml` file at the root directory for more details).

### How to integrate it in the Eclipse IDE

For Eclipse IDE users, make sure you have installed both:
* the Eclipse [Scala IDE](http://scala-ide.org "Scala IDE") plugin, depending on your Eclipse version ;
* the [m2eclipse-scala](https://github.com/sonatype/m2eclipse-scala "m2eclipse-scala") plugin at the following update site URL: [update site](http://alchim31.free.fr/m2e-scala/update-site). More information can be find on this [post](https://www.assembla.com/spaces/scala-ide/wiki/With_M2Eclipse "m2eclipse post").

### How to use it

In the way you acquire PowerAPI from [Maven](http://maven.apache.org "Maven") repositories, you can directly use it as a _Jar project_ in your `pom.xml` file.

In the way you acquire PowerAPI from our Git repository, you can navigate to your desired module and use it as a standard [Maven](http://maven.apache.org "Maven") module:

```bash
cd $POWERAPI_DIR/sensors/sensor-cpu-api
mvn test
```

### How to configure it

As said above, configuration part is managed by the [Typesafe Config](https://github.com/typesafehub/config "Typesafe Config"). Thus, Take care to properly configure each module from its `.conf` file(s).

Let us take an example for the `fr.inria.powerapi.formula.formula-cpu-dvfs` module, which implements the PowerAPI CPU `Formula` using the [CMOS CPU power formula](http://en.wikipedia.org/wiki/CMOS "CMOS CPU power formula"), `P = c * f * V * V`, where `c` constant, `f` a frequency and `V` its associated voltage.

To compute this formula, `fr.inria.powerapi.formula.formula-cpu-dvfs` module has to know:
* the CPU [Thermal Design Power](http://en.wikipedia.org/wiki/Thermal_design_power "Thermal Design Power") value;
* the array of frequency/voltage used by the CPU.

This information can be written in its associated configuration file as the following:

```
powerapi {
	cpu {
		tdp = 105
		frequencies = [
			{ value = 1800002, voltage = 1.31 }
			{ value = 2100002, voltage = 1.41 }
			{ value = 2400003, voltage = 1.5 }
		]
	}
}
```

Each module can have its own configuration part. See more details in its associated `README` file.

<h2 id="architecture-details">Architecture details</h2>

PowerAPI is based on a modular and asynchronous event-driven architecture using the [Akka library](http://akka.io "Akka library"). Architecture is centralized around a common [event bus](http://en.wikipedia.org/wiki/Event_monitoring "Event monitoring") where each module can publish/subscribe to sending events. One particularity of this architecture is that each module is in passive state and reacts to events sent by the common event bus.

These modules are organized as follow:

### Core

As its name indicates, the `Core` module gathers all *kernel* functionnalities that will be used by other modules. More particulary, this module defines the whole types used by PowerAPI to define its architecture.

This module also defines the essential `Clock` class, responsible of the periodically sending of the `Tick` message, later responsible of the process of the PowerAPI business part.

### Sensors

To compute the energy spent by a process through its hardware resource utilization, PowerAPI divides the computations in two parts:
1. Hardware resource process utilization monitoring;
2. Computation of the energy implies by the hardware resource process utilization.

Sensor modules or _Sensors_ represents a set of `Sensor`, responsible for the monitoring of hardware resource process utilization. Thus, there is a CPU `Sensor`, a memory `Sensor`, a disk `Sensor` and so on.
As information is given by operating system, there is one `Sensor` implementation by operating system type. Thus, there is a CPU Linux `Sensor`, a CPU Windows `Sensor`, and so on.

### Formulae

Set of `Formula`, responsible of the computation of the energy spent by a process on a particular hardware resource (e.g., CPU, memory, disk, network), following information provided by its associated `Sensor`.
A `Formula` may depend on the type of the monitored hardware resource. Thus, for the same hardware resource, several `Formula` implementations could exist.

### Processors

Set of `Processor`, which listen to `Formula` events sent by the common event bus in order to process them before to be displayed by `Reporters`. Thus `Processor` can be, for example, an aggregating process unit, aggregating the messages sent by `Formula` module to extract more meaningful information to display by a given `Reporter`.

### Reporters

Set of `Reporter`, which listen to `Processor` events in order to display them to the final user. Thus, a `Reporter` is _just_ a graphical representation of the PowerAPI request to the final user, which can be displayed, for example, in a console, a file or a graph.

### Library

The Library module defines the API that can be used by user to interact with PowerAPI.

<h2 id="api-details">API details</h2>

Process-level energy monitoring is based on a periodically computation that can be expressed via the API. Here there are several examples to describe PowerAPI's API:

### How to follow the CPU enegy consumption of process #123 every 500 ms?

Assume a process running under Linux, using a [procfs](http://en.wikipedia.org/wiki/Procfs "Procfs") file system on a _standard_ CPU architecture.
Thus, we need to use the _procfs_ CPU `Sensor` implementation and a given CPU `Formula` implementation, let's say the [DVFS](http://en.wikipedia.org/wiki/Voltage_and_frequency_scaling "DVFS") version. Add to this the desire to display CPU energy spent by process into a console. So we need to:

1. Activate the desired modules:

```scala
Array(
    classOf[fr.inria.powerapi.sensor.cpu.proc.times.CpuSensor],
    classOf[fr.inria.powerapi.formula.cpu.dvfs.CpuFormula]
).foreach(PowerAPI.startEnergyModule(_))
```

2. Ask to PowerAPI to provide the CPU energy spent by the process #123, every 500 milliseconds, using a _console Reporter_ and aggregating results by timestamp:

```scala
PowerAPI.startMonitoring(
    process = Process(123),
    duration = 500 milliseconds,
    processor = classOf[fr.inria.powerapi.processor.TimestampAggregator],
    listener = classOf[fr.inria.powerapi.reporter.ConsoleReporter],
)
```

Note that we use `listener` as parameter instead of `reporter` for legacy reasons.

### How can I display CPU energy consumption as a chart?

Based on the previous code, we simply have to add a new `Reporter` which will be able to display CPU energy consumption as a chart.
PowerAPI integrates a `Reporter` using the [JFreeChart](http://www.jfree.org/jfreechart "JFreeChart library") Java graph library. So let's add it to the PowerAPI system:

```scala
PowerAPI.startMonitoring(
    listener = classOf[fr.inria.powerapi.reporter.JFreeChartReporter]
)
```

<h2 id="future-works">Future works</h2>

We are working on new _energy modules_ (`Sensor` + `Formula`) development. If you are interested in joining, feel free to contact us via our [GitHub](https://github.com/rouvoy/powerapi "GitHub") webpage or email us at powerapi@inria.fr!

<h2 id="license">License</h2>

Copyright (C) 2013 Inria, University Lille 1.

PowerAPI is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

PowerAPI is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with PowerAPI. If not, see <http://www.gnu.org/licenses/>.
