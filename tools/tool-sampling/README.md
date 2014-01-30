# PowerAPI data sampling

Stress processor and gather computer's power consumption to get its energy profile by polynomial regression.

# System configuration

Libraries are required:
* `libbluetooth-dev` (not required if communication between PowerSpy / PowerAPI is already established)
* `stess`
* `cpulimit`
* `libatlas-base-dev`
* `libopenblas-base`

For the two last mentioned above, symbolic links are missing on some system. That's why, check if this module exists:
* `/usr/lib/libblas.so.3`
* `/usr/lib/liblapack.so.3`

If they don't exist, please enter these two commands as follows:
* `ln -s /usr/lib/libblas.so /usr/lib/libblas.so.3`
* `ln -s /usr/lib/liblapack.so /usr/lib/liblapack.so.3`

If this solution does not work, please check this link:
* `https://github.com/fommil/netlib-java/`