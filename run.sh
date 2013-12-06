#!/bin/bash

cd tools/tool-PowerAPI
if [ $1 == "-h" ]
then
  echo "Options:"
  echo "  * -config <vm1|vm2|vm3> (if present, it should be the first parameter)"
  echo "  * -pid <PID1>,<PID2> ..."
  echo "  * -app <APP1>,<APP2> ..."
  echo "  * -output <console|file|chart|virtio>"
  echo "  * -frequency <TIME_IN_MS>"
  echo "  * -filename <FILE_NAME>"
  echo "  * -sensor <cpu-proc|cpu-proc-reg>"
  echo "  * -formula <cpu-reg|cpu-max|cpu-maxvm>"
  exit 1
fi
if [ $1 == "-config" ]
then
  cp src/main/resources/virtio.conf.$2 src/main/resources/virtio.conf
  mvn scala:run -DaddArgs="$3 $4|$5 $6|$7 $8|$9 ${10}|${11} ${12}|${13} ${14}|${15} ${16}"
else
  mvn scala:run -DaddArgs="$1 $2|$3 $4|$5 $6|$7 $8|$9 ${10}|${11} ${12}|${13} ${14}"
fi
