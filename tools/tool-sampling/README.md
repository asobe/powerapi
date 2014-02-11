# PowerAPI data sampling

Stress processor and gather computer's power consumption to get its energy profile by polynomial regression.

# System configuration

Libraries are required:
* `libbluetooth-dev` (not required if communication between PowerSpy / PowerAPI is already established)
* `stess`
* `cpulimit`
* `libatlas-base`

Intall the following package:
* `libatlas-base-dev`

If the files `/usr/lib/liblapack.so.3` and `/usr/lib/libblas.so.3` does not exist, create the symbolic links:
* `ln -s /usr/lib/liblapack.so /usr/lib/liblapack.so.3`
* `ln -s /usr/lib/libblas.so /usr/lib/libblas.so.3`

If you need further details, see the link below:
* `https://github.com/fommil/netlib-java/`