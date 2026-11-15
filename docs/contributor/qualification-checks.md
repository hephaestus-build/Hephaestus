---
title: Qualification checks
description: The checks of the release qualification scripts, what each one measures, and the bar that decides it.
page_type: reference
---

This page defines each check that the qualification scripts write to an evidence folder.
[Qualify a release](./qualify-a-release.md) gives the procedure.
Each check has one of four statuses:

| Status | Meaning |
|---|---|
| `pass` | The check measured its signal, and the signal met the bar. |
| `fail` | The check measured its signal, and the signal did not meet the bar. |
| `recorded` | The check measured its signal. It has no bar, or the run did not have the load that the bar needs. |
| `not-run` | The check could not measure its signal. `needs` names the load, access or step that it needs. |

The verdict of a run is FAIL if a check failed.
It is PASS if every check passed or was recorded.
Otherwise it is PARTIAL.
A `not-run` check is never a pass: a missing reading is not a good reading.

## Bench

`scripts/qual-bench.ts` measures one or more windows.
With `--rounds` above 1, each check ID gets the suffix `-r<round>`.

### B3

Provider time to first token, from the first histogram that moved in the window: `gen_ai_client_operation_duration_seconds`, `llm_proxy_first_token_seconds` or `llm_pool_first_token_seconds`.
Recorded only.

### B4

The memory of each review sandbox and the duration of each review run in the window.
The check fails when a review sandbox reaches its memory request, or when a review sandbox is OOM-killed.
The review duration is recorded.
It has no bar until the qualification runs of the current sandbox architecture give a first-run median.

### B5

The p95 of `llm_pool_permit_wait_seconds` over all bands.
Recorded only.

### B7

The claim latency of review runs, by workspace.
It is recorded when the window has reviews of two workspaces.

### B8a

The server and worker Pods.
The bar has two parts:

- The anon plus kernel peak of the Pod's cgroup is at most 90 % of the memory limit.
  The page cache is not counted, because the kernel reclaims it before an OOM kill.
- Under the bench load, the memory limit is at most 1.5 times that peak.
  Without load, this part is recorded only.

The check also records the heap maximum, the GC rate and the CPU p95.

### B8b

The bundled PostgreSQL Pod.
Its anon plus kernel peak is at most 80 % of its memory limit.
Its peak connection count is at most 80 % of the sum of the Hikari pool maximums.

### B8d

The webhook Pod is at most 90 % of its memory limit.
The agent-sandbox controller and the agent image pre-pull Pods are at most their memory request.

### B8f

The node.
The memory requests of the release fit the allocatable memory with at least 1 GiB to spare.
Swap-in is 0, and the memory PSI `some avg10` stays below 10 %.
On k3d, the node is a container that shares the PSI and swap counters of its Docker VM, so the check is recorded only.

### B8-oom

No Pod outside the sandbox namespace is OOM-killed or evicted in the window.

### B8-tier

The chart's eviction order on a full node, from `scripts/kubernetes-memory-drill.ts`.
It runs only on a disposable k3d node, with `--memory-drill --images <release-images.tsv>`.

### B10

The latency of the practice profile overview route, from the server's own HTTP timer over the window.
The server p50 is at most 1 s and the p95 is at most 2 s.
`--overview-url` and `--token-file` add a client probe every 10 s; the client times are recorded beside the server times.

### B11

The database statements in the window, from `pg_stat_statements`.
`--statements` names statements by a regular expression over their normalized text:

```json
[{ "name": "feedback list", "pattern": "FROM feedback_copy .* WHERE" }]
```

No named statement is in the top 10 by total time, and the mean of each named statement is at most 50 ms.

## Drills

`scripts/qual-drills.ts` injects one failure per drill.
A drill injects nothing when no run is in flight, unless `--allow-idle` asks for the injection alone.

### D1

Kills one replica of the component that serves the sandbox gateway, with no grace period.

- The killed replica is replaced and Ready.
- No run is lost, and the review behind each cut run completes.
  A `PARTIAL` review with a withhold, such as `EXECUTION_INCOMPLETE`, completes. A review that ends `EXECUTION_FAILED` or `PREPARATION_FAILED` does not.
- Every model call permit that was open at the kill closes, or its lease lapses.
- No usage row of a cut call is `VERIFIED` with zero tokens.
- An `EMAIL` action that passed its write fence does not end `DONE` through a second attempt.

### D2

Deletes one claimed sandbox Pod, then one SandboxClaim.

- The run moves on to a new holder (epoch + 1), and no run is lost.
- The `agent_sandbox` row leaves after its claim is deleted.
- A subject that still has an open run gets a new sandbox.

### D3

A lost provider event.
The drill has no safe automated injection, so it prints the manual step.
With `--item`, it checks that the latest version of the item has a review and that the review of the old head completed.

### D4

Scales the agent-sandbox controller to 0 for `--minutes`, then back.

- A claim made during the downtime gets no Pod, and it starts when the controller returns.
- The sandboxes that ran before keep running.
- No run ends with a failure stop reason during the downtime, and the queued runs start after it.

### D5

A rolling update of the server and the worker (`--injection restart`), or a `helm upgrade` that someone runs during the drill (`--injection external`).

- With two server replicas and a PodDisruptionBudget, each component keeps an available replica.
- No run is cut, and the review behind each run completes.

## Turn smoke

`scripts/qual-turn-smoke.ts` asks one Ask Hephaestus question and judges the turn from its rows.
The check passes only when all of these are true:

- The turn ends `DONE` on its first attempt, and its `TURN` run ends `COMPLETED`.
- A conversation sandbox holds the conversation while the turn runs.
- At least one model call is recorded for the turn.
- The run's lease moves at least once, when the turn runs for 30 s or longer.
  The sandbox renews its lease every 25 s.
- The server's longest `GET /internal/agent/runs/{runId}/context` is at most 20 s.
  The holder's lease renewals wait behind a slow context read.

The last two parts find a holder that loses its lease, even when a later attempt ends the turn.
