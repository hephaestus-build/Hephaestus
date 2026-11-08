# ADR 0025: Agent job queue moves off NATS onto PostgreSQL

**Status:** Accepted (superseded in part 2026-11-09 by [ADR 0079](0079-one-agent-system.md))
**Date:** 2026-07-21
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md) (two-role runtime, original agent NATS consumer), [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md) (in-app LLM proxy as the sole credential path), [ADR 0013](0013-no-jetstream-dlq-stream.md) (no JetStream DLQ stream)

## Context

The practice-review agent job queue ran on a NATS JetStream stream
(`AGENT`) that carried nothing but job ids: a worker pulled an id off the stream, then loaded the
actual `agent_job` row from PostgreSQL to do anything with it. The `agent_job` table was already
the source of truth for job state, retry count, and outcome — JetStream only decided *which*
worker got to claim *which* id next, a job JetStream is not needed for once the claiming worker
reads and writes the same database the job lives in.

That left two systems asked to agree about one queue:

- **Duplicated guarantees.** `FOR UPDATE SKIP LOCKED` claiming, worker fencing via
  `worker_registry` heartbeats, retry budgets, and orphan-job sweeps were all already implementable
  — and, for orphan sweeps, already necessary — against Postgres alone, because a worker that dies
  mid-job leaves its row claimed but its JetStream ack pending, and only a Postgres-side sweep (not
  JetStream redelivery, see ADR 0013) can find that row. JetStream's ack/redelivery machinery
  (`ack-wait: 70m`, `max-deliver: 5`, `max-ack-pending`) was a second, independently-tuned copy of
  guarantees the row already needed to provide.
- **Drift risk.** An id resurfaces on the stream and the row it points at could already be
  claimed, cancelled, or gone (workspace purge) by the time a worker reads it — every consumer
  already had to re-check the row before trusting the id. The id-only stream added a
  synchronization surface with no independent guarantee of its own.
- **Infrastructure cost disproportionate to the smallest deployments.** A single-operator instance
  running the monolith (ADR 0005) needs NATS today only because the agent queue does — webhook
  ingest and SCM/Slack sync are the only remaining consumers, and self-host installs that skip
  those integrations entirely had no other reason to run a JetStream stream, size its memory/file
  limits, or reason about its `AGENT` stream in troubleshooting.

## Decision drivers

- Prefer one source of truth over two that must be kept synchronized by convention.
- Preserve every current guarantee (mutual-exclusion claiming, worker fencing, bounded retries,
  orphan recovery) — this is a delivery-mechanism change, not a semantics change.
- Reduce the operational surface for the smallest self-host deployments (ADR from #1381) without
  removing NATS where it is still load-bearing (webhook ingest, ADR 0008; SCM/Slack sync).
- Keep the worker's poll cost low enough that dropping push-based delivery is a non-issue in
  practice.

## Considered options

1. **Keep JetStream, harden it** — add a Postgres-side reconciliation pass that periodically
   re-validates every claimed id against the `agent_job` table, on top of the existing ack/retry
   config. Closes the drift risk but keeps both systems and their independent tuning knobs.
2. **Poll-based delivery straight from `agent_job`** — each worker replica polls the table on an
   interval, claims a batch with `FOR UPDATE SKIP LOCKED`, and the same table row is the only place
   retry count, ownership, and outcome live.
3. **Move to a different broker** — out of scope; would trade one duplicated-truth problem for
   another broker to run.

## Decision

Option 2. The agent job queue is delivered by polling `agent_job` directly:

- `AGENT_ENABLED` (default `false`) replaces `AGENT_NATS_ENABLED` as the flag that lets a JVM
  claim and execute jobs, combined with the worker runtime role exactly as before
  (`hephaestus.agent.enabled AND hephaestus.runtime.worker.enabled` — see ADR 0005's amendment).
- `AGENT_POLL_INTERVAL` (default `1s`) controls how often each eligible replica polls for claimable
  work.
- `AGENT_CLAIM_BATCH_SIZE` (default `5`) bounds how many rows one poll claims via
  `FOR UPDATE SKIP LOCKED`, so replicas divide the backlog instead of contending for it.
- `AGENT_MAX_RETRIES` (default `5`) replaces JetStream's `max-deliver` as the bound on how many
  times a job is retried before it is left failed.
- **Eligibility is a column, not a timer.** `agent_job.available_at` gates the candidate query, so a
  requeue (orphan recovery, worker drain, classified infra failure) schedules its own next attempt
  with backoff instead of becoming instantly reclaimable. This is what replaces JetStream's
  `ack-wait`.
- **The candidate query admits only runnable work.** A `(workspace, purpose)` already at the
  `max_concurrent_jobs` cap on its `workspace_agent_binding` row contributes zero candidates, so an
  unclaimable backlog can never fill the `LIMIT` window ahead of a job that could run. The per-row
  `FOR UPDATE` recheck inside the claim transaction stays the authoritative gate; a stale read here
  costs one skipped candidate.
- `AGENT_NATS_ENABLED`, `HEPHAESTUS_AGENT_NATS_SERVER`, `AGENT_NATS_MAX_ACK_PENDING`, and
  `AGENT_NATS_FETCH_BATCH_SIZE` are removed; the `AGENT` JetStream stream is abandoned (operators
  may delete it, `nats stream rm AGENT`, as optional cleanup — nothing depends on it existing or
  not).
- Worker fencing and orphan recovery are unchanged in intent: `worker_registry` heartbeats and
  Postgres-side claim expiry, already the actual mechanism that survived a worker dying mid-job, now
  govern claim loss uniformly instead of racing a JetStream ack-wait timer that pointed at the same
  row. A requeue is fenced on the worker id the caller identified as dead, so a belated second sweep
  cannot steal a job a live sibling has legitimately re-claimed.
- NATS is untouched everywhere else. Webhook ingest (ADR 0008) and SCM/Slack sync consumption keep
  their own JetStream streams and consumers; `NATS_ENABLED` / `HEPHAESTUS_SYNC_NATS_SERVER`
  continue to gate exactly what they gated before this change.

## Consequences

**Positive.**

- One source of truth for job state, claiming, and retries — no second system to keep in sync by
  convention, no id that can point at a row already claimed, cancelled, or purged elsewhere.
- Operational simplicity: a deployment that needs practice review but not webhook ingest or SCM/
  Slack sync no longer needs NATS running at all. The smallest self-host installs drop a stateful
  dependency entirely.
- Worst case, a claimable job waits up to `AGENT_POLL_INTERVAL` before a replica picks it up —
  bounded, predictable added latency at job start, not at job execution.

**Negative / accepted.**

- **Polling load.** Every eligible replica runs one cheap, indexed query per `AGENT_POLL_INTERVAL`
  (default: once a second) against `agent_job`, whether or not there is work. This scales linearly
  with worker replica count; it does not scale with job volume, so it stays cheap at the replica
  counts this system operates at today.
- **No push-based wakeup.** A job submitted the instant after a poll waits out the rest of the
  interval rather than being dispatched immediately, unlike JetStream's push consumer. Acceptable
  given the default 1s interval against jobs that run for minutes.
- **The queue is now our table to keep small.** A broker expired its own messages; a table does not.
  `agent_job` therefore carries a retention obligation the stream used to absorb — `AgentJobRetentionService`
  strips heavy payload columns past `AGENT_PAYLOAD_RETENTION` (default `P14D`) and deletes terminal
  rows past `AGENT_ROW_RETENTION` (default `P90D`), and the table is autovacuum-tuned for its churn.
  Retention on a queue table is a correctness surface, not housekeeping: it must never delete a row
  another table still references, nor one whose delivery has not landed.
- **The admission filter is not scheduling fairness.** It bounds the one starvation mode that was
  unbounded in time; among the candidates that survive it, ordering is still strictly global
  `available_at ASC, id ASC`. There are no per-workspace lanes, no round-robin, and no proportional
  share, so a workspace enqueuing a hundred jobs it is *entitled* to run — under its cap, or with no
  `workspace_agent_binding` row at all, which `COALESCE`s to unbounded — takes the whole candidate
  window ahead of a younger job from a quiet workspace. That wait is bounded by the busy workspace's
  cap, not by any fairness rule, and because the cap is per `(workspace, purpose)` a workspace
  running *k* purposes can hold *k* × its cap RUNNING at once. `max_concurrent_jobs` is the only
  lever; an instance with a noisy tenant should set it rather than expect the queue to arbitrate.
- **A requeue cannot cancel an LLM call already in flight.** Requeue rotates the job's proxy token,
  so no *new* request authenticates on the old one — but `JobTokenAuthenticationFilter` authenticates
  once at request entry and `LlmProxyController` then streams the upstream response without
  re-validating at chunk boundaries. A request already past authentication when the rotation happens
  streams to completion. Closing this would mean a mid-stream liveness check, which conflicts with
  running the proxy with no read-idle timeout (LLM SSE streams go silent while the model thinks).
  Accepted cost: a drain or infra-retry requeue during an active call can incur one extra concurrent
  call's spend until that call completes or hits `responseTimeout=300s`.

## Revisit trigger

Worker replica count grows to where polling load on `agent_job` becomes measurable against the
database's own budget; or a push-based wakeup (e.g. `LISTEN`/`NOTIFY`) becomes worth the added
complexity to shave the poll-interval latency off job start; or a tenant's wait behind a busy
workspace becomes a real complaint, at which point per-workspace fairness lanes (round-robin or
weighted-share dequeue) are the answer — deliberately deferred to the replay/backfill epic (#1354),
which needs them anyway.

## Update — 2026-10-14 (#2656): the job table and classes are renamed

`agent_job` is `review_job`. `AgentJobExecutor`, `AgentJobService`, `AgentJobZombieSweeper` and
`AgentJobRetentionService` are `ReviewJobExecutor`, `ReviewJobService`, `ReviewJobZombieSweeper` and
`ReviewJobRetentionService`.

## Update — 2026-11-03: no fixed number of running reviews for a workspace

`workspace_agent_binding.max_concurrent_jobs` is removed. A fixed count per workspace was a width that no measurement set.
The candidate query no longer excludes a slot at a cap.

§ Consequences called that cap "the only lever" against a noisy tenant. The claim order has since taken that role: started work first, then the workspace with fewer running reviews, then origin with aging, then age.
The fair grants of the call pool share the provider between workspaces ([ADR 0074](0074-a-reviews-width-and-count-follow-the-provider.md)).
A worker's review slots and the reviews quota still bound how many reviews run.

## Update — 2026-11-09: superseded in part by ADR 0079

[ADR 0079](0079-one-agent-system.md) supersedes the form of the queue. PostgreSQL stays the queue and the only record of work.

- One `agent_run` table holds the lease of every kind of agent work. `review_job` and `mentor_turn` point to their runs and keep their domain lifecycles.
- A claim uses `SKIP LOCKED` and wakes on `NOTIFY`. A fixed poll interval and a claim batch do not decide the start time.
- A claim orders by band, then by the workspace with the fewest running runs, then by age. This is the fairness that the revisit trigger named.
- A write is fenced on `(run_id, lease_epoch)`, not on a worker id. `worker_registry` and the sweeps by worker go.
- One reaper on the database clock runs on every server replica.
