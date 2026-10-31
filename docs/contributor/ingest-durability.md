---
title: Webhook delivery and retry order
description: The consumer's durability contract and its failure tests.
page_type: explanation
---

The consumer acknowledges an event after its handler commits.
Liveness reporting is best-effort and cannot repeat a completed handler.
Database and provider transport failures retry with bounded exponential delay and equal jitter.
The delivery count includes earlier transient attempts.
Unknown exceptions retry up to `hephaestus.integration.consumer.poison.max-redeliver` (default 10).
At that limit, the consumer commits a dead-letter record before termination.
Only a `PayloadParsingException` in the cause chain proves a permanent failure and terminates early, also after capture.
Capture failure always leaves the event retryable.
Stack overflows use the unknown-failure budget.
Other fatal JVM errors stop the local subscription and mark the consumer stopped, so reconciliation can restart it.

Each durable uses explicit acknowledgements, `MaxDeliver=-1` and `MaxAckPending=1`.
The broker delivery limit stays unlimited so an outage cannot exhaust it before PostgreSQL can record the failure.
The application applies the unknown-failure budget after each failed dispatch.
The broker holds the failed sequence ahead of later sequences, including after a consumer restart.
Order is serial per (workspace, stream), in stream sequence order, not provider event time.
GitHub, GitLab, Slack and Outline use separate streams.
There is no shared inbox and no total order across them.
The GitHub installation durable has no workspace and serializes all installation lifecycle events.
A failed installation event delays later installation events until success or capture.
The server runs as one replica with Recreate; this contract requires no consumer leader lock.

Copies of a sequence already queued or in progress share one queue entry.
A redelivery after dispatch can repeat a committed handler.
Existing event identities must keep those handlers idempotent.
A missing acknowledgement times out after the configured `ack-wait`.
Explicit negative acknowledgements carry their own retry delay.
See [NATS pull consumers](https://docs.nats.io/learn/jetstream/pull-consumers).

`consumer_dead_letter` stores the workspace, consumer, stream, sequence, subject, broker arrival time and diagnostic cause class.
It stores no payload, headers, exception message or routing credential.
Sanitized cause types and stack frames are logged on the first delivery and after each committed capture.
The unique consumer, stream and sequence key makes capture idempotent across a crash between commit and termination.
Workspace deletion cascades to its rows.
Installation rows use NULL and separate instance-admin queries.
All workspace list and replay queries include the workspace predicate.

Replay reads the original event from NATS before opening a database transaction.
The original broker timestamp and routing headers reach the dispatcher unchanged.
The lookup compares the subject and arrival time to reject a replaced stream's reused sequence.
Replay and live dispatch share a local lock for the workspace and stream.
Replay is an explicit late event; it does not rewind subsequent events or establish order across streams.
A row lock keeps concurrent replay requests from applying the same row twice.
Participating handler database writes and replay completion share one transaction, so those partial writes roll back on failure.
Replay holds this transaction during provider calls.
Handler preparation that uses a separate transaction can still commit independently.
A successful replay records the requesting account and completion time on the row.
Failed replay preserves the pending row.
Operators follow [Replay a failed webhook event](../admin/webhook-replay).

`IngestDurabilityIntegrationTest` pauses a private PostgreSQL container while real NATS redelivers a proven transient failure beyond the configured delivery count.
It checks that the second event waits and that a consumer restart preserves the failed head.
`ConsumerDeadLetterIntegrationTest` uses real NATS and PostgreSQL for capture, lost termination, failed capture, permissions and replay.
`IntegrationPoisonHandlerTest` covers classification through wrapped causes and the unknown-failure budget.
