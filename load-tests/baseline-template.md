# Hephaestus capacity baseline

Generated from the scenario's `handleSummary` JSON and its runner metadata. Do not hand-edit metrics
or verdicts. One document describes one scenario on one host in one repetition.

## Automated evidence

{{RUN}}

### Effective workload

{{SCENARIOS}}

### Thresholds

| Metric | Objective | Result |
| --- | --- | --- |
| {{THRESHOLDS}} | | |

### Measurements

Trend durations are milliseconds; `med` is p50. Rates are fractions unless the metric is a counter's
per-second `rate`. HTTP timings do not isolate gateway or provider time.

| Metric | Statistic | Value |
| --- | --- | --- |
| {{METRICS}} | | |

## Operator evidence required for qualification

The summary cannot establish host sizing. Publish a companion record with all of the following;
missing evidence means the host remains **unqualified**, even if every threshold passes:

- Operator, commit, release-lock digest and signature verification, rendered Compose digest, all
  application and sandbox image digests; Linux, Docker and Compose versions.
- Host provider, region, CPU model, vCPUs, RAM and storage; load-generator host and utilization.
- Snapshot identity, row counts and inbox state; deterministic provider identity, model and
  latency distribution. Any gateway limit recorded above must match the deployed configuration, not
  an assumed default.
- Raw container and host samples, busiest rolling 60-second CPU window, minimum available memory,
  filesystem space before/after, swap, OOM/restarts and queue drain time.
- Accepted webhook / publish-counter reconciliation; gateway throttling, request-size rejection and
  provider error evidence. A throttled run measures the limiter, not stack capacity.
- Every raw summary and run metadata file across three repetitions for each candidate host and
  scenario, the median, first failing load step and failure mode.
- Minimum qualified host, recommended qualified host with measured headroom, and limits outside
  that envelope. Publish these in the admin installation guide with links to all evidence.
