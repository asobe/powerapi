#!/bin/bash

(
echo $BASHPID; kill -s SIGSTOP $BASHPID;
while true; 
  do
     sleep 1
  done
)
