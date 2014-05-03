#!/bin/bash

(( $# > 2)) || { echo "Usage: ./start_bench.bash #abs_dir_spec #name #duration"; exit 1;}

# Benchmark launching
(
  cd $1
  . ./shrc

  runspec --noreportable --iterations=1 $2 > /dev/null &
  sleep $3
)

# Stop benchmark
name=$(ps -ef | grep _base.amd64-m64-gcc43-nn | head -n 1 | cut -d '/' -f 6 | cut -d ' ' -f1)
killall -s KILL specperl runspec specinvoke $name &> /dev/null

exit 0;