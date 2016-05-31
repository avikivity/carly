#!/bin/bash

/usr/sbin/sshd -h /host_rsa
while true; do
    sleep 1m
    echo -n .
done
