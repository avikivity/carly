#!/usr/bin/env python3
import sys
import subprocess
import re

def containers():
    lxcOutput = subprocess.check_output( ['lxc', 'list' ] )
    ips = re.compile( '\d+\.\d+\.\d+\.\d+' ).findall( str( lxcOutput ) )
    nodes = dict( hosts = ips, vars = { "ansible_user": "root" } )
    inventory = dict( nodes = nodes )
    return inventory

import argparse
import json
parser = argparse.ArgumentParser()
parser.add_argument( '--list', action = 'store_true' )
parser.add_argument( '--host' )
arguments = parser.parse_args()

if arguments.list:
    json.dump( containers(), sys.stdout )
if arguments.host:
    json.dump( {}, sys.stdout )
