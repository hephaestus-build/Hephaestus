#!/bin/sh
# Arguments: the stage's seconds, the tokens its scripts may spend on models, and the workspace.

workspace=${3:-/workspace}
node=$(node -p 'process.execPath')
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
    # The stage receives the precompute credential and never the review's LLM_PROXY_TOKEN.
    env -i HOME=/home/agent PATH=/usr/local/bin:/usr/bin:/bin TMPDIR=/tmp \
        LLM_PROXY_URL="${LLM_PROXY_URL:-}" PRECOMPUTE_PROXY_TOKEN="${PRECOMPUTE_PROXY_TOKEN:-}" \
        setsid timeout --foreground --signal=KILL "$1" \
        "$node" --permission --allow-fs-read="$workspace" --allow-fs-read=/opt/precompute \
        --allow-fs-write="$stage*" --allow-fs-write="$output*" --allow-child-process \
        "$workspace/pi-precompute.ts" "$workspace" "$1" "$2" > "$log" 2>&1 &
    pid=$!
    wait "$pid"
    status=$?
    kill -KILL "-$pid" 2>/dev/null || true
    exit "$status"
) || {
    printf '%s\n' '[precompute] failed, continuing without hints' >&2
    # The runner writes each practice's section and result whole, as a pair, when that practice
    # finishes. Those pairs stay; every other file is partial or not the runner's, and goes.
    mkdir -p "$output"
    for file in "$output"/* "$output"/.[!.]*; do
        [ -e "$file" ] || continue
        case "$file" in
        *.md) [ -f "${file%.md}.json" ] && continue ;;
        *.json) [ -f "${file%.json}.md" ] && continue ;;
        esac
        rm -rf "$file"
    done
    tail -c 8192 "$log" > "$output/precompute-runner.log" 2>/dev/null || true
    cat "$output/precompute-runner.log" >&2 2>/dev/null || true
}

exit 0
