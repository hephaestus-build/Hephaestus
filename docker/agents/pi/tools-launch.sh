#!/bin/sh
# Starts the tools program under its system call filter (tools-seccomp.ts), so that a command there can open no socket
# but a Unix one. A conversation's tools Pod serves its host on TCP: its tools program starts without the filter, puts
# every command under it, and takes no inspector signal, so no command reaches the program's own port or its runtime.
FILTER="/opt/pi-sdk/tools-seccomp-$(uname -m).bpf"
if [ "${TOOLS_ROLE:-}" = "conversation" ]; then
	TOOLS_COMMAND_FILTER="$FILTER" exec setpriv --no-new-privs \
		node --disable-sigusr1 --enable-source-maps /opt/pi-sdk/tools-server.js
fi
exec setpriv --no-new-privs --seccomp-filter "$FILTER" node --enable-source-maps /opt/pi-sdk/tools-server.js
