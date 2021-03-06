#!/bin/bash

if [[ "$#" -eq 1 && $1 == "-h" ]]
then
  echo "Options:"
  echo "  * -pid <PID1>,<PID2> ..."
  echo "  * -app <APP1>,<APP2> ..."
  echo "  * -aggregator <timestamp|device|process>"
  echo "  * -output <console|file|gnuplot|chart|virtio|thrift>,<console|file|gnuplot|chart|virtio|thrift>, ..."
  echo "  * -frequency <TIME_IN_MS>"
  echo "  * -time <TIME_IN_MIN>"
  echo "  * -filename <FILE_NAME>"
  echo "  * -cpusensor <cpu-proc|cpu-proc-reg|cpu-proc-virtio|sensor-libpfm|sensor-libpfm-core-process>"
  echo "  * -cpuformula <cpu-max|cpu-maxvm|cpu-reg|formula-libpfm|formula-libpfm-core-cycles>"
  echo "  * -memsensor <mem-proc|mem-sigar>"
  echo "  * -memformula <mem-single>"
  echo "  * -disksensor <disk-proc|disk-atop>"
  echo "  * -diskformula <disk-single>"
  echo "  * -vm <PID1:portnr1>,<PID2:portnr2> ..."
  echo "  * -powerspy <1|0>"
  exit 1
fi

ulimit -n 4096
sudo mvn install
sudo java -jar target/tool-PowerAPI.jar "-classpath src/main/resources" "${1} ${2}" "${3} ${4}" "${5} ${6}" "${7} ${8}" "${9} ${10}" "${11} ${12}" "${13} ${14}" "${15} ${16}" "${17} ${18}" "${19} ${20}" "${21} ${22}" "${23} ${24}" "${25} ${26}" "${27} ${28}" "${29} ${30}" "${31} ${32}" "${33} ${34}"
