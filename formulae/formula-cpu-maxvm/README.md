# Implementation of the PowerAPI CPU Formula module by polynomial curve fitting

## Presentation

Implements PowerAPI CPU Formula module with using the result of the polynomial curve fitting form the sampling tool.

## In

Conform to `fr.inria.powerapi.formula.formula-cpu-api`.

## Out

Conform to `fr.inria.powerapi.formula.formula-cpu-api`.

## Configuration part

To provide CPU energy spent by a process, this module has to know the coefficients of the polynomial representing the formula.

For example:
```
powerapi {
  formula {
    coeffs = [
      { value = 6.7476468907802865 }
      { value = 133.27549174479248 }
      { value = -147.00068570951598 }
      { value = 55.2647624073449 }
    ]
  }
}
```
