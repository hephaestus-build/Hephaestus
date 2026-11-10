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
- `provider_event` holds the payload, the headers needed for dispatch, the provider, the scope key, the arrival time, the state (`QUEUED`, `LEASED`, `DONE`, `DEAD`), the attempt, `leased_until` and the stop reason. A row that a workspace owns carries `workspace_id` from its first migration. An installation event has no workspace, as today.
- A claim takes the oldest `QUEUED` row of a scope with `FOR UPDATE SKIP LOCKED`. A partial unique index allows one `LEASED` row per scope. This is the claim and lease of `agent_run`, on the database clock. A `NOTIFY` wakes an idle claimer.
- The reaper requeues an expired lease. At the attempt budget, the row becomes `DEAD` and keeps its payload. A replay sets `DEAD` back to `QUEUED`.
- `consumer_inbox_step` keys by the event id. Handlers keep their named steps.
- The receiver reads Slack consent and the active Outline routes from PostgreSQL directly. The key-value projections and their rebuilds go. Outline secrets stay derived from the routing secret.
- Erasure deletes the rows of an account, a channel or a workspace in its own transaction. A `DONE` row goes after the replay window (default 3 days).
- NATS, its chart templates, its credential, its certificate, the `HostnameVerifiedNatsDataPort` and the webhook role go.

### Timing

The change lands before the 1.0 tag, in the cutover of [ADR 0070](0070-1-0-is-a-cutover-release.md).
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
- **The upgrade drops a short gap.** The upgrade stops the receiver, waits until the stream is empty, and starts the new release. Deliveries in the gap are lost, and the sync repairs them.

## Revisit trigger

- Event volume makes the inbox a measurable share of database load.
- An operator needs reception during planned database maintenance that the sync cannot repair.
