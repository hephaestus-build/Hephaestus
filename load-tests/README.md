# Compose capacity qualification

For an isolated Kubernetes course benchmark, use the
[contributor procedure](../docs/contributor/capacity-benchmark.md).
It reuses these k6 adapters and adds open session arrivals, replay provenance and a stream observer.

These [Grafana k6](https://grafana.com/docs/k6/latest/) scenarios measure the supported single-host
Compose topology. They spend LLM budget and create durable rows; run them only on an isolated host.

Webhook traffic uses a [constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/).
Pass/fail uses [k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/). Sizing requires
both service objectives and saturation, following Google's SLO guidance
([SRE Workbook](https://sre.google/workbook/implementing-slos/)).

## Safety and prerequisites

- Deploy the exact release from `docker/self-host/compose.yaml` on a dedicated Linux host. Run k6
  elsewhere.
- Populate a representative workspace before testing. `ARTIFACT_IDS` must contain distinct, reviewable
  pull requests so each request performs work rather than hitting deduplication/cooldown.
- Bind practice review and mentor to a deterministic OpenAI-compatible test provider. Record its
  response latency separately: provider time is not host capacity.
- Use a short-lived workspace-admin token. Never commit it or the webhook secret.
- Disable background sync and unrelated traffic. Restore PostgreSQL and JetStream from the same
  snapshot before every repetition at each candidate host and load step. Run three times; publish
  the median and retain every raw result.

## Run

The load scenarios are uncached and manual. CI runs `test:load:syntax`, which inspects every
scenario and tests their contracts using the same digest-pinned k6 image, with networking disabled.
`vp run check` runs the runner's own tests.

```bash
vp run test:load:syntax
export BASE_URL=https://hephaestus-load.example.test
export LOAD_TEST_ACKNOWLEDGE=isolated-host
export WEBHOOK_SECRET=...
vp run test:load:webhook-burst

export AUTH_TOKEN=... WORKSPACE_SLUG=capacity-test
export ARTIFACT_IDS=101,102,103,104,105
export REVIEW_REQUESTS=5 MENTOR_VUS=5 REVIEW_VUS=2
# Copy the actual deployed values from the isolated host; neither value changes that host.
export SANDBOX_API_MAX_REQUEST_BYTES=4194304
export SANDBOX_API_REQUESTS_PER_MINUTE=120
vp run test:load:detection-mentor
```

`heph.js` loads Heph alone, with no review and no `ARTIFACT_IDS`. Each simulated developer has one
main conversation and `THREADS_PER_DEVELOPER` (default 2) threads, each with one question in flight.
`STAGES` ramps the developers, for example `1:2m,5:3m,15:3m,30:3m`. Give each developer its own
token in `AUTH_TOKENS` (comma-separated, at least one per developer at the peak), because the
`mentor-turns` limit counts the questions of one account, and the follow-up queue is per conversation:

```bash
export AUTH_TOKENS=...,... WORKSPACE_SLUG=capacity-test STAGES=1:2m,5:3m,15:3m,30:3m
vp run test:load:heph
```

To load a server on this machine, k6 must use the host network, and the server serves the API at
its root:

```bash
export LOAD_TEST_NETWORK=host API_PREFIX= BASE_URL=http://127.0.0.1:8080
```

`BASE_URL` is the externally reachable application root (without `/api`); every scenario uses it.
The gateway remains private, and only the detection+mentor workload crosses it, so only that
scenario takes and records the gateway limits.
Optional workload inputs are documented by the scenario's defaults.
Keep mentor traffic running long enough to overlap practice reviews; the default mentor phase is
10 minutes, while slow reviews can take longer. Each mentor VU asks once at the top level of the
token owner's Heph conversation, then asks again in the thread of that message, so the VUs run as
concurrent conversations. This is a finite concurrent-conversation workload, not an arrival-rate
claim for mentor throughput ([open versus closed models](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/)).
All mentor VUs ask as the token owner, so they share one `mentor-turns` rate limit: 20 questions in
10 minutes by default. For a run that asks more, raise `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_CAPACITY` on
the isolated host ([Runtime roles](../docs/admin/runtime-roles.mdx#live-answers)).

Each invocation prints a fresh `load-results/<scenario>-<timestamp>` directory. Set `LOAD_RESULTS_DIR`
to choose another **unused** directory. It contains `summary.json`, `run.json` (inputs, image,
start time, effective scenarios/thresholds from `k6 inspect`, and exit status), the baseline template,
and a `scripts/` snapshot. k6 inspects and runs that snapshot, not a mutable checkout. Keep the whole
directory so defaults and workload code remain reproducible after the checkout changes.
Secret values are not written to metadata or command arguments.
Artifacts can still contain sensitive workspace identifiers: inspect them before publication.
A failed k6 threshold preserves k6's nonzero exit status; keep failed runs, too.

Generate the document from that directory, including failed runs:

```bash
vp run report:load:baseline load-results/<scenario>-<timestamp>
```

This renders the saved `baseline-template.md` to `<run-directory>/baseline.md`, reporting every
threshold and metric without recomputing k6's verdict. It requires every threshold captured by
`k6 inspect`, valid runner metadata, and the gateway limits the scenario records. A setup failure or
interrupted export with missing evidence is rejected. Any digest-pinned run stays renderable, and
the document names the k6 image the run used and whether the checkout has moved on since. A passing
k6 result alone does not qualify a host: supply the companion operator evidence listed in the
document. Do not commit tokens, environment files or unredacted Compose configuration.

A mentor VU submits a turn, then polls the conversation page for the status of its question.
`detection-mentor.js` sets the poll interval. `mentor_turn_duration` measures completion to within that
interval. It does not measure the time to the first text of the answer.

## Collect host evidence

Webhook bursts land on the `webhook-server` container and detection+mentor traffic on
`application-server`, so collect both containers and read the busy one against the scenario you ran.

On the **Compose host**, create a fresh evidence directory for each run. From that directory, start
these collectors in separate terminals before starting k6 on the load-generator host:

```bash
docker stats --format json > container-stats.jsonl
```

```bash
vmstat -y -t 1 > host-vmstat.txt
```

Collect all containers, including review sandboxes. Docker's Linux CLI memory figure subtracts
cache; retain the raw samples rather than only a peak percentage
([`docker stats` semantics](https://docs.docker.com/reference/cli/docker/container/stats/)).
The [`vmstat` options](https://man7.org/linux/man-pages/man8/vmstat.8.html) timestamp samples and
omit the since-boot report. Also configure your host monitor to record `MemAvailable` from
`/proc/meminfo` throughout the run; `vmstat` reports free memory, not available memory. Retain
timestamped samples so the qualification rule below can be checked.

After load stops, keep the collectors running until the review queue drains or the five-minute
drain deadline expires.
Capture `df -h` before and after the run. Retain `docker compose ps`, `docker info`, the release lock,
provider latency, PostgreSQL size, JetStream state, and application metrics/logs. Copy the evidence
into its matching k6 run directory; never overwrite another repetition's samples.
The increase in `webhook.publish{outcome="success"}` must equal accepted
webhook iterations while the failure counter remains unchanged. Reject a run with a reconciliation
mismatch, dropped iterations, provider throttling, a container restart/OOM, or swap activity.

## Qualification matrix and sizing rule

Run both scenarios at the **minimum** and **recommended** host shapes, then increase webhook rate and
mentor/review concurrency independently until the first threshold or saturation failure. Do not add
the maxima together: that invents a workload never tested.

The documented size qualifies only when all three repeated runs meet thresholds, the busiest rolling
60-second host-CPU window remains below 80%, host available memory stays above 15% in every sample,
the data filesystem has at least 20% free before and after, no swap/OOM/restart occurs, and the review
queue drains within five minutes after load stops. Publish the first failing step as the ceiling.

Generate a baseline for every repetition and link its raw artifacts. Publish measured minimum and
recommended sizing and the JetStream durability throughput delta with the recorded baseline under
the [release capacity gate](https://github.com/hephaestus-build/Hephaestus/issues/1377).
Qualification must measure the release candidate, including its job-folder implementation.

## What the thresholds prove (and do not prove)

The scenario `options.thresholds` own the numerical budgets; generated baselines report them with
k6's verdict. Do not maintain a second set of threshold values in documentation.

- Webhook burst offers constant-arrival-rate ingress traffic and fails on dropped iterations.
  Synthetic `capacity_test` events measure authenticated publication, **not** downstream sync of
  real provider events. Acceptance requires `202` with `status: "ok"`; intentionally dropped events
  also return `202` but must not count as published. Neither scenario follows redirects.
- Only GitHub deliveries are measured. GitLab and Outline share the ingest path, so their capacity
  is read from this scenario rather than measured separately; Slack is not measured, because its
  publish gate and fast-path timeout make it a different workload that needs its own scenario.
- The mixed scenario requires every requested review to finish successfully. Exact completion
  counts catch interrupted iterations that never add a sample to a completion-rate metric.
- Mentor HTTP failures and whole-turn success are independent of polling traffic. A turn counts as
  answered only when it ends `DONE`.
- `heph.js` reads a turn's progress from the conversation's active turns every `POLL_MS`. Its
  `heph_queue_wait`, `heph_first_partial` and `heph_first_text` are therefore accurate to that
  interval. They include the provider's own latency: compare them with a direct call to the provider.
  A provider gateway that buffers its event stream delivers the whole answer at once, so the first
  text then arrives with the end of the turn.
- These are workload budgets, not an isolated gateway latency SLO or time-to-first-token SLO.
  Inspect gateway metrics for [the dedicated connector](https://github.com/hephaestus-build/Hephaestus/issues/1730)
  and reject capacity qualification on `429`/`413` or provider throttling, including failures hidden
  inside streamed responses. Do not raise the production limiter just to make a benchmark pass.
- k6 cannot prove CPU, memory, disk, queue drain or ingest reconciliation from HTTP summaries.
  The operator evidence and qualification rule above remain mandatory.
