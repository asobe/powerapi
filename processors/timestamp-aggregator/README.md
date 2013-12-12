# PowerAPI FormulaMessage aggregator by timestamp

Aggregate `FormulaMessage` by timestamp.

## In

Listen to `FormulaMessage`, which are typically provided by `Formula` modules.

## Out

Send `ProcessedMessage`, and more particularly `AggregatedMessage`, gathering each `FormulaMessage` associated by the same timestamp.

## Configuration part

This module has to know the parameter to smooth the energy data.

For example:
```
powerapi {
  aggregator {
    smoothing {
      state = true
      freq = 60.0
      mincutoff = 3.0
      beta = 0.007
    }
  }
}
```
