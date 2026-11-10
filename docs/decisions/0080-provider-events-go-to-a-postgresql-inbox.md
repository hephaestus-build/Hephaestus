# ADR 0080: Provider events go to a PostgreSQL inbox, and the server receives them

**Status:** Accepted (2026-11-10)
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich
**Supersedes:** [ADR 0066](0066-the-webhook-receiver-has-no-database.md). Parts of [ADR 0008](0008-webhook-runtime-role.md) (the webhook role and JetStream) and [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md) (the role list).
**Amends:** [ADR 0079](0079-one-agent-system.md) § Runtime components (no webhook role, no NATS).

## Context

The webhook role verifies a provider delivery and publishes it to NATS JetStream.
Each server consumes one durable per workspace and stream, with one unacknowledged message at a time.
PostgreSQL already holds every other fact of an event: the committed handler steps, the dead letters, the replay records and the activation of a workspace.
The state of one event is thus split across two stores.
The recent ingest defects are at that seam (ING-11, ING-12, the reuse of a sequence by a replaced stream, retention that reads the broker, the replay fence).
NATS also adds a second lease model, which [ADR 0079](0079-one-agent-system.md) § Good rules out ("one lease, one claim, one reaper").
It adds a stateful service with its own volume, certificate and credential (SEC-5, SEC-7).
NATS cannot erase the content of one Slack channel or Outline document before the stream ages out.

## Decision drivers

- One lease model, one claim and one reaper for all queued work.
- Erasure reaches every copy of a person's content.
- The fewest stateful services that an operator must run, back up and secure.
- Order per scope and at-least-once handling across server replicas.

## Considered options

1. **Keep NATS JetStream** (ADR 0008, ADR 0066). Reception survives a database outage. All the costs in the context stay.
2. **A PostgreSQL inbox, received by the server (chosen).**
3. **A PostgreSQL inbox, with a separate receiver role.** Rejected: once the receiver needs PostgreSQL, a separate Deployment of the same JAR only keeps reception alive during a server restart. Several server replicas with rolling updates already do that.

## Decision

- The server receives every provider delivery on the existing `/webhooks/**` paths. It verifies the delivery and inserts one `provider_event` row in one short transaction. Then it answers `202`. The in-flight budget and the size limit stay.
- **Deduplication stays.** Each row carries the provider and the delivery key that the provider's `SubjectKeyDeriver.deriveDedupKey` derives today (the `Nats-Msg-Id` of ADR 0008). A unique index on the provider and that key makes the insert `ON CONFLICT DO NOTHING`, and the server answers `202` for the copy. A redelivery thus never gets a second `provider_event` id, so `consumer_inbox_step` cannot repeat a handler's effects. The key stays unique for as long as the row exists, which is at least the replay window and so longer than the 3-hour window of ADR 0008.
- `provider_event` holds the payload, the headers needed for dispatch, the provider, the scope key, the arrival time, the state (`QUEUED`, `LEASED`, `DONE`, `DEAD`), the attempt, `leased_until` and the stop reason. A row that a workspace owns carries `workspace_id` from its first migration. An installation event has no workspace, as today.
- A claim takes the oldest `QUEUED` row of a scope with `FOR UPDATE SKIP LOCKED`. A partial unique index allows one `LEASED` row per scope. This is the claim and lease of `agent_run`, on the database clock. A `NOTIFY` wakes an idle claimer.
- **Handler writes are fenced.** A claim raises the row's `lease_epoch`. Each write transaction of a handler first locks its `provider_event` row and checks that it is still `LEASED` with that epoch, as a run's holder does ([ADR 0079](0079-one-agent-system.md)). The `consumer_inbox_step` claim is part of that check.
- The reaper requeues an expired lease. At the attempt budget, the row becomes `DEAD` and keeps its payload. A replay sets `DEAD` back to `QUEUED`.
- `consumer_inbox_step` keys by the event id. Handlers keep their named steps.
- The receiver reads Slack consent and the active Outline routes from PostgreSQL directly. The key-value projections and their rebuilds go. Outline secrets stay derived from the routing secret.
- **Erasure fences in-flight handlers.** Erasure of an account, a channel or a workspace locks and deletes the matching `provider_event` rows, the leased ones included, in its own transaction. A handler that passed its checks before the erasure then fails its fence check in its next write transaction, and it rolls back. For example, the Outline handler exports a document before its write transaction, and that write finds its event gone. Content that a handler had committed before the erasure is erased with the rest. Thus no erased content comes back after the erasure commits.
- A `DONE` row goes after the replay window (default 3 days).
- NATS, its chart templates, its credential, its certificate, the `HostnameVerifiedNatsDataPort` and the webhook role go.

### The runtime that follows

- **The webhook role folds into the server.** With the inbox, a separate receiver keeps nothing alive that the server replicas do not. In 1.0, one JAR has one runtime role, the server, plus the migrate run mode.
- **The webapp keeps its nginx image.** It is stateless, it owns the security headers and the SPA fallback, and it has no open defect. To serve it from the JAR would make the Gradle build depend on the pnpm build and move the headers into Spring. That saves one Deployment, and the cost is not worth it for 1.0.

### Timing

The change lands before the 1.0 tag, in the cutover of [ADR 0070](0070-1-0-is-a-cutover-release.md).
The receiver folds into the server only together with, or after, the server's move to several replicas with rolling updates and a disruption budget.
Today the chart runs one server replica with `Recreate`, so a restart of a folded receiver would stop reception. The fold must not ship before that rollout.
1.0 carries no compatibility for the old path. NATS and the webhook role are deleted, not kept behind a flag.

## Consequences

### Good

- One store holds an event. ING-12 cannot happen, and the activation boundary of ING-11 is a row id.
- One lease model for events, runs and actions.
- An operator runs one stateful service fewer. The backup covers events.
- Erasure reaches event payloads.

### What gets harder

- **A database outage stops reception.** Providers that do not retry lose those deliveries.
  The routine sync repairs lifecycle facts. A GitHub recovery job redelivers failed deliveries through the App API. Slack retries by itself.
  Transient activity that only a GitLab or Outline event carries is lost.
- **Payloads sit in PostgreSQL.** The table needs retention and a size alert. A 25 MB GitHub payload goes to TOAST.
- **The upgrade drops a short gap.** The upgrade stops the receiver, waits until the stream is empty, and starts the new release. Deliveries in the gap are lost. The routine sync repairs the lifecycle facts of the work, but it cannot recover every delivery: transient GitLab or Outline activity in the gap is lost.

## Revisit trigger

- The chart is otherwise final and one Deployment fewer matters to operators. Then serve the webapp from the server.
- Event volume makes the inbox a measurable share of database load.
- An operator needs reception during planned database maintenance that the sync cannot repair.
