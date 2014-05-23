# PowerAPI launcher

A simple tool to create a PowerAPI app supporting the following parameters:
* -pid PID1,PID2 ...
* -app APP1,APP2 ...
* -aggregator timestamp|device|process
* -output console|file|gnuplot|chart|virtio|thrift,console|file|gnuplot|chart|virtio|thrift ...
* -frequency TIME_IN_MS
* -time TIME_IN_MIN
* -filename FILE_NAME
* -cpusensor cpu-proc|cpu-proc-reg|cpu-proc-virtio|sensor-libpfm
* -cpuformula cpu-max|cpu-maxvm|cpu-reg|formula-libpfm
* -memsensor mem-proc|mem-sigar
* -memformula mem-single
* -disksensor disk-proc|disk-atop
* -diskformula disk-single
* -vm PID1:portnr1,PID2:portnr2 ...
* -powerspy 1|0

 See run.sh to use PowerAPI with a command line.