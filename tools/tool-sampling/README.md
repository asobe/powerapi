# Learning the processor energy profile

This phase depends on a powermeter. For our development, we used PowerSPY (see the README in the corresponding sensor).
Some workloads are used to stress the processor along its features.
Hardware counters are collected during each of them.
Then, we applied a multivariate regression to obtain the energy profiles corresponding to all the available frequencies of the processor.
The obtained model is available in `libpfm_formula.conf` in this folder.

### System configuration

Check all the `.conf` files. Several hardware informations are required.
Several external libraries are used in this module. See the list below:
* `stress`
* `cpulimit`
* On linux platform, OpenBLAS from `http://www.openblas.net/` is required. The compilation from source is preferable. Then, please add these symlinks:
  * ln -s $installation_dir/libopenblas_$library_name-r0.2.8.so /usr/lib/libblas.so.3
  * ln -s $installation_dir/libopenblas_$library_name-r0.2.8.so /usr/lib/liblapack.so.3

Please read the documentation of the `sensor-powerspy` before to use this module.

### How to use it ?

`./run.sh`