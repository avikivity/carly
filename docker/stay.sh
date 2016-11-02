#!/bin/bash


_die() {
    echo $1
    exit -1
}

/usr/sbin/sshd -h /host_rsa || _die "sshd error"

while true; do
    sleep 1m
    echo -n .
done
