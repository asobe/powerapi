#!/bin/bash

cd tools/tool-PowerAPI
if [ $1 == "-h" ]
then
  echo "Options:"
  echo "  * -config <vm1|vm2|vm3> (if present, it should be the first parameter)" 
  echo "  * -pid <PID1>,<PID2> ..."
  echo "  * -app <APP1>,<APP2> ..."
  echo "  * -aggregator <device|process>"
  echo "  * -output <console|file|gnuplot|chart|virtio>"
  echo "  * -frequency <TIME_IN_MS>"
  echo "  * -filename <FILE_NAME>"
  echo "  * -cpusensor <cpu-proc|cpu-proc-reg>"
  echo "  * -cpuformula <cpu-max|cpu-max-vm|cpu-reg>"
  echo "  * -memsensor <mem-proc|mem-sigar>"
  echo "  * -memformula <mem-single>"
  echo "  * -disksensor <disk-proc|disk-atop>"
  echo "  * -diskformula <disk-single>"
  exit 1
fi
if [ $1 == "-config" ]
then
  cp src/main/resources/virtio.conf.$2 src/main/resources/virtio.conf
  mvn scala:run -DaddArgs="$3 $4|$5 $6|$7 $8|$9 ${10}|${11} ${12}|${13} ${14}|${15} ${16}|${17} ${18}|${19} ${20}|${21} ${22}|${23} ${24}|${25} ${26}|${27} ${28}"
else
  mvn scala:run -DaddArgs="$1 $2|$3 $4|$5 $6|$7 $8|$9 ${10}|${11} ${12}|${13} ${14}|${15} ${16}|${17} ${18}|${19} ${20}|${21} ${22}|${23} ${24}|${25} ${26}"
fi
