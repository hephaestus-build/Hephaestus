#!/bin/sh

workspace=${2:-/workspace}
node=$(command -v node)
stage="$workspace/work/precompute-stage"
output="$workspace/work/precompute-out"
log="$workspace/work/precompute-runner.log"

(
    # POSIX sh measures this per-file limit in 512-byte blocks (10 MiB).
    ulimit -f 20480 &&
    mkdir -p "$stage" "$output" &&
    # Node permissions prohibit creating this fixed image-library symlink.
    ln -sfn /opt/precompute/lib "$stage/lib" || exit 1
    # A separate process group lets us reap ordinary children even after a successful run.
    env -i HOME=/home/agent PATH=/usr/local/bin:/usr/bin:/bin TMPDIR=/tmp \
        setsid timeout --foreground --signal=KILL "$1" \
        "$node" --permission --allow-fs-read="$workspace" --allow-fs-read=/opt/precompute \
        --allow-fs-write="$stage*" --allow-fs-write="$output*" --allow-child-process \
        "$workspace/pi-precompute.ts" "$workspace" > "$log" 2>&1 &
    pid=$!
    wait "$pid"
    status=$?
    kill -KILL "-$pid" 2>/dev/null || true
    exit "$status"
) || {
    printf '%s\n' '[precompute] failed, continuing without hints' >&2
    rm -rf "$output"
    mkdir -p "$output"
    tail -c 8192 "$log" > "$output/precompute-runner.log" 2>/dev/null || true
    cat "$output/precompute-runner.log" >&2 2>/dev/null || true
}

exit 0
