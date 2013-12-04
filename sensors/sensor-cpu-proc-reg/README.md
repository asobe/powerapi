# Polynomial curve fitting implementation of the PowerAPI CPU Sensor module (based on sensor-cpu-proc)

## Presentation

Extends the `fr.inria.powerapi.sensor.cpu.sensor-cpu-proc` module in providing processor activity, useful for implementation of the formula provided by polynomial curve fitting.

See also: [Man proc](http://linux.die.net/man/5/proc "proc manual").

## In

Conform to `fr.inria.powerapi.sensor.sensor-cpu-api`, without filling the `TimeInStates` field

## Out

Conform to `fr.inria.powerapi.sensor.sensor-cpu-api`.
```
