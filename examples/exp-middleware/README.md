# Middleware example

This example was used to do test different setups described as follows:
* default: simple example which provides a graphical chart to compare the estimated power consumption to the real one;
* SPECCpuExp: experiment with benchmarks provided by SPEC CPU. All the values are logged into files. These files are then processed to sum up to obtain the energy consumption along the run. It provides also some statistics obtained during all the runs (which could be changed in the code).Be careful of the applied license;
* StressExp: same as the previous experiment, excepts that the stress command is used (until the processor limits).