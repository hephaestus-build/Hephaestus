---
title: Webhook delivery and retry order
description: The consumer's durability contract and its failure tests.
page_type: explanation
---

The consumer acknowledges an event only after its handler and liveness writes succeed.
A PostgreSQL outage of 1, 15 or 60 minutes has the same outcome: the event remains unacknowledged.
Provider outages and unclassified exceptions retry indefinitely, within the stream's retention.
A repeated failure does not prove that an event is poison.

Each durable uses explicit acknowledgements, `MaxDeliver=-1` and `MaxAckPending=1`.
The last setting applies across every subscription bound to the durable.
Thus, the broker holds the failed sequence ahead of later sequences, including after a consumer restart.
Order is stream sequence order for each workspace, not provider event time.
Independent provider streams have no shared sequence and no total order.

Copies of the sequence already queued or in progress share one queue entry.
Thus, a redelivery storm cannot fill the local queue with copies of a slow event.
A redelivery after dispatch can repeat a committed handler.
Existing event identities must keep those handlers idempotent.

The broker's backoff list applies to acknowledgement timeouts.
Its first timeout preserves the configured acknowledgement wait for slow handlers.
An explicit negative acknowledgement supplies its own bounded delay with equal jitter.
A finite delivery limit would stop delivery during a long outage, so it cannot enforce this contract.
See [NATS consumer configuration](https://docs.nats.io/nats-concepts/jetstream/consumers).

Only `PayloadParsingException`, raised at the payload boundary, proves a permanent failure.
The consumer commits the original envelope in `consumer_dead_letter` before `term()`.
Capture failure leaves the message retryable.
The unique consumer, stream and sequence key makes capture idempotent across a crash between commit and termination.

Workspace records carry their workspace from the first migration.
Installation lifecycle events have no workspace yet.
Their rows use NULL and separate instance-admin queries.
All workspace list and replay queries include the workspace predicate.

Replay locks one dead-letter row and invokes the original dispatcher and route admission.
It preserves the original broker metadata, which SCM handlers use for event identity.
Successful replay records its completion in the transaction.
Failed replay preserves the pending row.
Operators follow [Replay a failed webhook event](../admin/webhook-replay).

`IngestDurabilityIntegrationTest` pauses a private PostgreSQL container while real NATS redelivers past the old retry limit.
It checks that the second event waits and that a consumer restart preserves the failed head.
The retry intervals are accelerated.
The test does not wait an hour.
`IntegrationPoisonHandlerTest` covers large delivery counts, provider failures, poison capture and bounded retry delay.
`ConsumerDeadLetterIntegrationTest` covers durable capture, workspace isolation, permissions and replay state.
