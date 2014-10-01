#!/bin/bash

# This script is used to compile one given benchmark.
(( $# == 2)) || { echo "Usage: ./compile_bench.bash #abs_dir_spec #name"; exit 1;}

cd $1
. ./shrc

runspec -a build $2 > /dev/null

exit 0;