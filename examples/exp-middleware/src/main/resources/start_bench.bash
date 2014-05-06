#!/bin/bash

# This script is used to launch one given benchmark (represented by its name) during a fixed duration.
(( $# == 2)) || { echo "Usage: ./start_bench.bash #abs_dir_spec #name"; exit 1;}

cd $1
. ./shrc

runspec --noreportable --iterations=1 $2

# Stop the benchmark if it does not terminate correctly.
name=$(ps -ef | grep _base.amd64-m64-gcc43-nn | head -n 1 | cut -d '/' -f 6 | cut -d ' ' -f1)
killall -s KILL specperl runspec specinvoke $name &> /dev/null

exit 0;