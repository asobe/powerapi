#!/bin/bash

ulimit -n 4096
sudo mvn install
sudo java -jar target/tool-sampling.jar "-classpath src/main/resources"