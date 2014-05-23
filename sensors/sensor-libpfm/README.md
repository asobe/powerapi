# Sensor component used to HW performance counters.

## Presentation

This component is used to read the hardware performance counters. A binding of the libpfm library is used (http://sourceforge.net/p/perfmon2/libpfm4/ci/master/tree/) to read these values.
This binding depends (for the moment) on the UNIX 64 bits systems for compatibility problems in the generated jar. It was well-tested on the kernel 3.8.0.030800 (works also for KVM processes on it).
The business part of this binding is written in `CUtils.java` and `LibpmUtil.scala`.
Each sensor is responsible to get the counter values for one given event. So it produces one sensor message per tick/event.

## In

This module reacts to `Tick` messages, typically sent by the core `ClockWorker` class.

## Out

This module provides specific sensor messages which are represented by the `LibpfmSensorMessage` type, and gather:
* the value of the hardware performance counter it supports;
* the corresponding event name;
* the `Tick` responsible to this reading.

## Configuration part

To provide the hardware counter values, this module has to know:
* the configuration part for the libpfm library, which is represented by each `powerapi.libpfm.configuration.*` field.
* the monitored events, which are represented by `powerapi.libpfm.events` string array.