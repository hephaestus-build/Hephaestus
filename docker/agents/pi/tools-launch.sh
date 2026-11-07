#!/bin/sh
# Starts the tools program of a review Pod under its system call filter (tools-seccomp.ts).
exec setpriv --no-new-privs --seccomp-filter "/opt/pi-sdk/tools-seccomp-$(uname -m).bpf" \
	node --enable-source-maps /opt/pi-sdk/tools-server.js
