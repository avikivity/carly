#!/bin/bash


_die() {
    echo $1
    exit -1
}

sudo /usr/sbin/sshd -h /host_rsa || _die "sshd error"
/start-scylla || _die "scylla error"

while true; do
    sleep 1m
    echo -n .
done
