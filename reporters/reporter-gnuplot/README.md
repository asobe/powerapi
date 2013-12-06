# PowerAPI gnuplot reporter

Display `AggregatedMessage` into a gnuplot data file.

## In

Listen to `AggregatedMessage`, which are typically provided by `fr.inria.powerapi.listener.listener-aggregator` module.

## Out

Display energy information into a gnuplot data file.

## Configuration part

This module has to know the path prefix of the output gnuplot data file.

For example:
```
powerapi {
	reporter {
		gnuplot {
			prefix = "/path/to/the/output/file/prefix"
		}
	}
}
```
