#!/bin/sh
# The entrypoint of the image built by the Dockerfile beside this file. It preloads
# pg_stat_statements and, in a container with a memory limit, sizes PostgreSQL from that limit. Then
# it runs the official entrypoint (docs/admin/sizing.mdx).
#
# shared_buffers is 25 % of the limit, the starting value that the PostgreSQL manual (Resource
# consumption) gives; the manual also says that a larger shared_buffers needs a larger max_wal_size.
# Without a limit, PostgreSQL keeps its defaults: the host's memory is not PostgreSQL's to take. These
# flags come before the ones the container is started with, so a flag in a Compose `command` wins.
set -eu

case "${1:-}" in
	-*) set -- postgres "$@" ;;
esac

if [ "${1:-}" = postgres ]; then
	shift
	case "${1:-}" in
		--version | -V | --help | '-?') exec docker-entrypoint.sh postgres "$@" ;;
	esac
	# cgroup v2, then v1. v1 reports a near-2^63 value for no limit, which the total below catches.
	limit=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo max)
	# awk prints a large product in exponent notation, so it reads kB and the shell multiplies.
	total=$(($(awk '/^MemTotal:/ { print $2 }' /proc/meminfo) * 1024))
	sizing=""
	case "$limit" in
		'' | *[!0-9]*) ;;
		*)
			if [ "$limit" -lt "$total" ]; then
				mb=$((limit / 1048576))
				maintenance=$((mb / 16))
				maintenance=$((maintenance < 64 ? 64 : maintenance > 1024 ? 1024 : maintenance))
				wal=$((mb / 2 < 1024 ? 1024 : mb / 2))
				sizing="-c shared_buffers=$((mb / 4))MB -c effective_cache_size=$((mb * 3 / 4))MB"
				sizing="$sizing -c maintenance_work_mem=${maintenance}MB -c max_wal_size=${wal}MB"
			fi
			;;
	esac
	# $sizing holds only flags and numbers, so word splitting is safe here.
	set -- postgres -c shared_preload_libraries=pg_stat_statements $sizing "$@"
fi

exec docker-entrypoint.sh "$@"
