#!/bin/bash

ulimit -n 4096
sudo mvn clean
sudo mvn install
sudo java -jar target/exp-middleware.jar "-classpath src/main/resources"