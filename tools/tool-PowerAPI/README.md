# PowerAPI launcher

A simple tool to create a PowerAPI app supporting the following parameters:
 * -config <vm1|vm2|vm3> (if present, it should be the first parameter)
 * -pid <PID1>,<PID2> ...
 * -app <APP1>,<APP2> ...
 * -aggregator <device|process>
 * -output <console|file|gnuplot|chart|virtio>
 * -frequency <TIME_IN_MS>
 * -filename <FILE_NAME>
 * -cpusensor <cpu-proc|cpu-proc-reg>
 * -cpuformula <cpu-max|cpu-max-vm|cpu-reg>
 * -memsensor <mem-proc|mem-sigar>
 * -memformula <mem-single>
 * -disksensor <disk-proc|disk-atop>
 * -diskformula <disk-single>
