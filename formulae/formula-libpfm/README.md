# Implementation of the PowerAPI Formula for libpfm sensor by using the raw informations produced by the LibpfmSensor

## Presentation

Implements the PowerAPI Libpfm formula. For each tick, this component has to receive n messages corresponding to the number of monitored events.
So, the business part is composed by two actors. First, a listener (`LibpfmListener`) treats the LibpfmSensorMessage.
When the number of required messages or a new tick is received, it sends a LibpfmListenerMessage which contains the values of HW counters for a given tick and process.
Then, the LibpfmFormula reacts on these messages and uses the informations provided by `cpufreq-utils` to compute the energy consumption of a given process related to the time spent in each frequency.
The `cpufreq-utils` tool is not required. You can disable by putting the parameter to false in the configuration file.

## In

This component reacts to `LibpfmListenerMessage', which are provided by its companion listener.

## Out

This module provides the result of the formula computation which is represented by the `LibpfmFormulaMessage` type, and gather:
* the energy value;
* the `Tick` responsible to this computation result;
* the string device name, thus `cpu`;

## Configuration part

To provide CPU energy spent by a process, this module has to know:
* the number of threads (powerapi.cpu.threads);
* whether the cpufreq-utils is enabled (powerapi.cpu.cpufreq-utils);