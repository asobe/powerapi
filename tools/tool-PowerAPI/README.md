# PowerAPI launcher

A simple tool to create a PowerAPI app supporting the following parameters:
 * -pid <PID1>,<PID2> ...
 * -vm <PID1:PORT1>,<PID2:PORT2> ...
 * -app <APP1>,<APP2> ...
 * -appscont <1|0> #continously monitors for the app name, even if not present in the beginning of execution
 * -aggregator <device|process>
 * -output <console|file|gnuplot|chart|virtio>
 * -frequency <TIME_IN_MS>
 * -time <EXECUTION_TIME_IN_MIN>
 * -filename <FILE_NAME>
 * -cpusensor <cpu-proc|cpu-proc-reg|cpu-proc-virtio>
 * -cpuformula <cpu-max|cpu-maxvm|cpu-reg|>
 * -memsensor <mem-proc|mem-sigar>
 * -memformula <mem-single>
 * -disksensor <disk-proc|disk-atop>
 * -diskformula <disk-single>

 See run.sh to use PowerAPI with a command line.