# ADR 0013: No dedicated JetStream DLQ stream — poison handler ACKs after N redeliveries

**Status:** Accepted
**Date:** 2026-05-25
**Authors:** Integration framework polish (#1198), Wave 9 revert

## Context

Pass 9 (commit `976b4dbf9`) added a dedicated JetStream **dead-letter stream** + DLQ
consumer to capture messages that exceeded `maxRedeliver` instead of letting
`IntegrationPoisonHandler` ACK them with a counter bump. The next audit pass reverted
it (commit `b6970db0b`). This ADR records *why* the revert is the standing design.

The original argument for a DLQ stream:

- Operator visibility into the actual bytes that failed (rather than only
  `integration.consumer.poison` Micrometer counter).
- Replay-after-fix workflow without re-running the upstream vendor delivery.
- Symmetry with the agent-runtime DLQ stream (which exists for a different reason —
  long-running agent jobs whose container died mid-execution).

What the revert noticed:

- **The failure modes don't want a DLQ.** `IntegrationPoisonHandler` NAKs with
  exponential backoff up to `maxRedeliver` (default 10), then ACKs + bumps
  `integration.consumer.poison`. The failures it sees are DB constraint races on
  upsert (resolved on retry), sync-target removed mid-flight (legitimate skip), and
  malformed payloads from vendor schema drift. The first two are recoverable on
  retry, so a DLQ would just flap on them — operationally worse than no DLQ.
- **Replay-after-fix is not a current workflow.** No oncall procedure documents
  "drain DLQ and re-inject"; vendors retry on their own schedule, and if Hephaestus
  is the broken party the fix is a code change followed by the next vendor poll
  cycle (GitHub) or a manual operator replay.
- **The counter + WARN log is the operator signal.** A non-zero
  `integration.consumer.poison` rate triggers a Grafana alert; the log carries
  `sanitizeForLog(subject)` so operators reason about scope without payload bytes.
  The `integration_consumer_dlq` table pass-9 added was never queried by any
  dashboard or runbook.

## Decision

`IntegrationPoisonHandler` is the only DLQ mechanism:

- `maxRedeliver = 10` (configurable via `hephaestus.integration.consumer.poison.max-redeliver`).
- Exponential backoff `clamp(base * 2^attempt + jitter, maxDelay)` — `baseDelay = 2s`,
  `maxDelay = 5m`, `jitter = 1s`.
- At `deliveredCount() >= maxRedeliver`: ACK the message (stop occupying an inflight
  slot), bump `integration.consumer.poison{kind=<kind>}` counter, log ERROR with
  `sanitizeForLog(subject)`.
- No persistent record of poisoned message bytes. The operator's primary signal is
  the counter rate.

No `dlq` stream is provisioned in `WebhookJetStreamBootstrap`. No `IntegrationDlqConsumer` bean
exists. No `integration_consumer_dlq` table exists in Liquibase.

## Considered options

1. **Dedicated DLQ JetStream stream with retention=-1.** Rejected for the reasons in
   Context: flap on transient races, no replay workflow, no dashboards consuming the
   bytes, persistent capacity cost for unactioned data.
2. **DLQ as a separate subject filter on the SAME stream** (e.g. publish poisoned
   messages to `dlq.<kind>.<subject>`). Rejected: still flap-prone, still no replay
   workflow, AND mixes "real traffic" + "operator audit trail" on one stream's storage
   budget.
3. **External store (S3) or an in-process bounded buffer.** Rejected: more
   infrastructure (or lost on pod restart), and neither solves the underlying
   "no one is going to replay this" problem — both are already covered by the
   structured log line.

## Consequences

**Positive:**

- Zero operational overhead for the DLQ surface that no one queries.
- Counter-based alerting is the right granularity for the actual failure modes.
- Vendors handle their own retry; we don't duplicate vendor's persistence.
- Simpler infrastructure (one fewer JetStream stream, one fewer Liquibase table).

**Negative:**

- If a future use case actually needs replay-from-DLQ semantics (e.g. a regulatory
  audit requirement: "prove every webhook was processed"), this design has to be
  revisited. The trigger condition is documented below.

## Revisit trigger

Re-open this decision if any of the following land:

1. **Regulatory requirement** to retain every webhook payload regardless of processing
   outcome (e.g. SOC 2 evidence collection for a security audit).
2. **`integration.consumer.poison` counter rate > 0.1 events/hour sustained for a
   week** — at that point we have a class of recurring failures that *would* benefit
   from replay-after-fix.
3. **A vendor adds at-most-once delivery** (current vendors are at-least-once; retries
   absorb transient failures).

## References

- `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/consumer/IntegrationPoisonHandler.java`
- Revert: commit `b6970db0b`
- Original pass-9 add: commit `976b4dbf9`
- Counter dashboard: Grafana panel `integration.consumer.poison{kind}` (per-vendor rate)

## Update — 2026-10-30 (PR #3463)

This update supersedes the Decision and Consequences sections for retry limits and poison-message storage.
Temporary outages must not exhaust a retry budget.
The consumer retries proven temporary failures without a delivery limit.
Unknown failures exhaust a retry budget.
Permanent failures and exhausted unknown failures have a metadata record in PostgreSQL before termination.
The decision to avoid a second JetStream stream remains.
[Webhook delivery and retry order](../contributor/ingest-durability.md) owns the current contract.
