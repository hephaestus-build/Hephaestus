#!/bin/sh
# Waits until a resource reports a condition as True, for at most a number of seconds:
#   sh k3s-wait-condition.sh <namespace, or - for a cluster-scoped resource> <kind/name> <condition> <seconds>
# `kubectl wait --for=condition=...` fails at once while the resource has no status yet, as a
# cert-manager Certificate has none in its first seconds. So this asks again until the deadline.
set -eu

usage() {
	echo "usage: k3s-wait-condition.sh <namespace|-> <kind/name> <condition> <seconds>" >&2
	exit 64
}

[ $# -eq 4 ] || usage
namespace=$1
resource=$2
condition=$3
seconds=$4
case $seconds in '' | *[!0-9]*) usage ;; esac

if [ "$namespace" = - ]; then set -- get "$resource"; else set -- -n "$namespace" get "$resource"; fi
# kubectl's errors go to a file: one in the captured output would never read as True.
errors=$(mktemp)
trap 'rm -f "$errors"' EXIT

deadline=$(($(date +%s) + seconds))
while :; do
	status=$(kubectl --request-timeout=10s "$@" -o "jsonpath={.status.conditions[?(@.type==\"$condition\")].status}" 2>"$errors") || status=
	if [ "$status" = True ]; then
		exit 0
	fi
	if [ "$(date +%s)" -ge "$deadline" ]; then
		echo "k3s-wait-condition: $resource is not $condition after $seconds seconds" >&2
		if [ -s "$errors" ]; then
			sed 's/^/    kubectl: /' "$errors" >&2
		fi
		exit 1
	fi
	sleep 2
done
