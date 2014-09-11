#!/bin/bash

cd $1

source env.sh

parsecmgmt -a run -n $2 -i native -p $3 &>/dev/null &

exit 0
