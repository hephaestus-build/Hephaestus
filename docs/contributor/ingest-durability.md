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

The server reconciles active workspaces once per minute when NATS is enabled.
It reattaches stopped subscriptions and recreates a durable that the broker removed.
An existing durable keeps its acknowledgement position; a fresh consumer starts with new deliveries.
Suspended, purged and filtered-out workspaces stay stopped, and disabled adapters stay unbound.
A workspace stop keeps its restart fence until the transaction completes.
A rollback re-arms consumption; a committed purge keeps its permanent fence.

Each durable uses explicit acknowledgements, `MaxDeliver=-1` and `MaxAckPending=1`.
The broker delivery limit stays unlimited so an outage cannot exhaust it before PostgreSQL can record the failure.
The application applies the unknown-failure budget after each failed dispatch.
The broker holds the failed sequence ahead of later sequences, including after a consumer restart.
Order is serial per (workspace, stream), in stream sequence order, not provider event time.
GitHub, GitLab, Slack and Outline use separate streams.
Delivery keys include the stream; there is no total order across streams.
The GitHub installation durable has no workspace and serializes all installation lifecycle events.
A failed installation event delays later installation events until success or capture.
The server runs as one replica with Recreate; this contract requires no consumer leader lock.

Synchronous dispatch carries the workspace that the consumer selected.
GitHub repository and team handlers use that workspace.
A related issue uses it only when the workspace also monitors the related repository.
Shared organization logins and shared monitored repositories do not select a different workspace.
The dispatch scope ends when handling returns or throws and does not cross to asynchronous work.

Copies of a sequence already queued or in progress share one queue entry.
A redelivery after dispatch uses the same database inbox identity.
A completed delivery skips the handler, including its provider preparation.
A delivery that failed after a partial commit skips its committed write steps and resumes unfinished steps.
Natural event identities still deduplicate separate deliveries of the same provider event.
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
[The stored work only moves forward](./sync-lifecycle.md#a-redelivered-or-older-payload-changes-nothing) defines provider versions and equal timestamps.
Handler dispatch runs without an outer database transaction, so dispatch holds no replay row lock or pooled connection.
The live path and replay use the same inbox keys.
Each step commits its key with its database writes; a failed step rolls back both.
A row lock serializes the final replay completion and installation audit in a short transaction.
If completion fails after a step committed, a retry keeps that step and finishes the replay record.

A successful replay records the requesting account and completion time on the row.
Failed replay preserves the pending row.
Operators follow [Replay a failed webhook event](../admin/webhook-replay).

## The database inbox

`consumer_inbox_step` belongs to a workspace from its first migration.
Its `workspace_id` is NOT NULL and workspace deletion cascades to its rows.
Workspace purge also removes its delivery keys through `ConsumerInboxPurge`; other workspaces and installation keys stay.
`installation_inbox_step` is instance-owned: an installation event can create the workspace it needs.
Both tables contain broker metadata and step names, without a payload, headers or person data.

The unique key is (workspace, consumer, stream, stream sequence, broker arrival time, step name).
The installation key has no workspace component.
Broker arrival time distinguishes a retained sequence from the same sequence in a replacement stream.
The delivery count is not part of the key.
A redelivery and an administrator replay must keep the original identity.

`DeliveryInbox` binds this identity on the thread that executes dispatch and removes it in `finally`.
`DeliveryInbox.claim` inserts a named step with `ON CONFLICT DO NOTHING` inside the business transaction.
A conflict skips that step's writes.
A single-step event uses a fixed operation name, without message or direct-message channel identifiers.
A loop names each step by a stable item id, such as repository id and commit SHA, rather than the loop position.
Step names are part of stored delivery keys.
Keep them stable across releases while those deliveries can remain in the broker.
Calls from a sync job have no broker identity and follow their usual natural-key and version rules.
A read can reconstruct the context that a later step needs after a committed step is skipped.
The `complete` key is written only after the whole handler returns successfully.
Failures still reach the consumer's retry and poison budget, also when an earlier step committed.

Orchestration handlers read providers outside their write transactions.
`GitHubInstallationMessageHandler` still verifies installation status inside its default handler transaction.
That call holds the transaction until the status read returns.
A process can stop after an HTTP or SMTP side effect and before its database completion record.
Such an external effect can repeat unless its adapter has a provider idempotency key or a durable claim-and-reconcile protocol.
The inbox guarantees database steps; it does not make a remote service part of a PostgreSQL transaction.

Cleanup runs every 6 hours on the server role.
It reads the broker's age limits outside the cleanup transaction.
It retains keys for the largest live or configured `hephaestus.webhook.stream.max-age` or `max-age-by-stream` value, plus one day.
A withheld limit reduction can leave the live bound longer than the configured one.
An unavailable or unbounded stream keeps the keys and records a cleanup failure.
Disabled NATS skips cleanup.
The server and webhook roles must use the same stream retention configuration.
The broker rejects replay after the original event has expired.
Cleanup failure leaves keys in place and increments `integration_consumer_inbox_retention_failures_total`.
[Alert response](../admin/production-operations-runbook#alert-response) owns the operator response.

## Handler boundaries

| Handler | Atomic database steps and retry decision |
| --- | --- |
| GitHub organization, repository, member, issue, issue comment, sub-issue, issue dependency, review, review comment, milestone, label, installation, installation repositories, installation target, membership, team repository and team handlers | The default `AbstractIntegrationMessageHandler` transaction claims one handler step with the writes and event publications in that transaction. User and label upserts commit separately and use natural keys. Installation handlers use the instance inbox. |
| GitLab member, project event, note, milestone, subgroup and issue handlers | The same default handler boundary. Admission first verifies the route and provider data. Its project discovery and monitor changes have separate atomic steps. |
| GitHub pull request, status and check suite handlers | Claim the event write in their existing short transaction. Reconstruct processing context on retry, then finish check reads and per-pull-request refresh steps. |
| GitHub review thread and GitLab pipeline handlers | Claim the event write in their own transaction. |
| GitHub and GitLab push handlers | Claim webhook persistence and each commit-details write. A failed GitLab commit rolls back its step; the delivery retries after the other commits finish. Repository id and SHA identify each item. Check-run writes have their own step. JGit traversal and write callbacks execute on the dispatch thread. |
| GitLab merge request handler | Claim the event write and readiness write separately. Provider reads stay outside both transactions. |
| Slack channel message handler | Claim message insert, edit, delete and channel erase writes. An edit retry can finish its timestamp write after the initial message insert committed. |
| Slack channel lifecycle and member-joined handlers | Claim registration, pause, revoke and rename writes with the consent state and audit. An ephemeral join notice is an external effect. |
| Slack app-uninstalled and tokens-revoked handlers | Claim the connection transition and database erasure in the uninstall transaction. |
| Slack mentor DM and session-stopped handlers | Claim submission, edit, deletion and stop writes inside the active-connection transaction. The turn request id remains a natural idempotency key. |
| Slack App Home handler | Reads database state and publishes the remote view. It has no database write step. |
| Outline webhook handler | Mirror document writes and event recording have separate `REQUIRES_NEW` steps. A retry after the mirror commit finishes the missing event before the equal-version fast path. Collection writes use stable collection ids and a purpose. Failure and poison accounting remains per attempt. |

A listener that writes inside the publisher's transaction joins its inbox step.
An `AFTER_COMMIT` listener cannot roll back that step.
An asynchronous listener or durable job is separate work; it does not inherit the broker thread's inbox identity.
Its existing natural keys, state transitions or delivery protocol own its idempotency.
Do not carry a broker step into a later job by thread-local context.

## Other ingestion listeners

| Listener | Boundary and retry decision |
| --- | --- |
| `ScmItemEventRecorder`, `ScmRepositoryItemLifecycle`, `IntegrationAttentionService` and `ProviderTeardown.onActivated` | Synchronous or `BEFORE_COMMIT` writes join the publisher's transaction and inbox step. Item event identities also prevent duplicate occasions. |
| `ActivityEventListener` | Its `AFTER_COMMIT` methods open separate transactions and catch recording failures. The activity write is best-effort. Redelivery skips a committed handler step, so it cannot repair a missing activity row. |
| `CommitEnrichmentEventListener` | Async work has no broker inbox scope. It enriches commits without contributor rows; the next repository sync repairs a missed enrichment. |
| GitHub, GitLab, Slack and Outline connection-state listeners, `SlackDataSyncScheduler` and `OutlineCollectionAdminService.onCollectionResumed` | Start independent initialization, sync or teardown work after commit. Their sync, connection generation and provider-registration rules own repeated execution. |
| `SlackChannelConsentProjection` and `OutlineRouteProjection` | Rebuild the current remote admission set. Repeated set updates are idempotent; scheduled rebuilds repair missed projections. |
| `ConnectionActivityRecorder` and `SyncPushService` | Invalidate local lookups or send UI refresh hints after commit. These effects do not write provider state. |
| `ActivityScmMirrorErasureListener` and `ItemEventEvidenceErasure` | Synchronous erasure joins its publisher's transaction. Repeated scoped deletes or evidence removal retain the erased state. |

Startup listeners and scheduled sweepers do not consume broker deliveries.
They retain their own job and lifecycle rules.

## Event publication registry listeners

The registry commits a publication with the business transaction that publishes it.
Its completion advisor can run after the listener's business transaction commits.
Therefore a registry redelivery still needs an idempotency rule; the framework completion row alone is not proof.

| Listener | Existing rule on redelivery |
| --- | --- |
| `ItemEventReviewListener.on(ItemEventRecorded)` | Workspace lock, terminal event state and compare-and-set decisions prevent a second review for the same occasion. |
| `ItemEventReviewListener.on(ItemSourceObserved)` | Reconciles currentness against the stored version; repeated reconciliation retains the same result state. |
| `ReviewCommandListener.on(BotCommandReceivedEvent)` | The provider, comment id and kind derive the review request key. A refusal reply is an external effect. |
| `ReviewCommandListener.on(CommentRequestedReviewEnded)` | Reads the recorded review outcome and replies through the provider. The remote reply retains its delivery limitation. |
| `ReviewJobCompletionService.cancel` | Cancels only queued or running jobs, under the job lifecycle's state guard. |
| `MentorTurnCompletion.billTakenBack` | Attempt usage settles by turn and attempt. Repeated billing cannot add a second charge; counters can repeat. |
| `MentorTurnCompletion.settle` | Settles the remaining conversation-copy claims by their persisted receipt identity. |
| `SlackThreadEventScheduler.onReviewCompleted` | A version check advances the reviewed watermark to the same thread timestamp. |
| `SlackWeeklyDigestPointer.on` | Uses the stored message claim and receipt; uncertain sends are reconciled from Slack history rather than posted again. |
| `ProviderTeardown.on` | Connection lock, credentials generation and pending teardown state fence the work. Provider teardown calls retain their external-effect limitation. |
| `ExportGenerationWorker.on` | Locks the export row and assembles only a pending export; bundle and ready state commit together. |
| `AccountHardDeleteSweeper.on` | The erasure locks the account and its request and skips each store that it already erased. A purge that does not finish stays `DELETING` for the hourly sweep. |
| `AccountSecurityEmailListener.on` | Resolves the security-event recipient and sends SMTP outside a transaction; delivery can repeat after a crash. |
| `ProductFeedbackEmailListener.on` | Checks the feedback, expiry and subscription. SMTP delivery can repeat after a crash. |
| `ProductFeedbackUpdateEmailListener.on` | Checks the feedback update, expiry and subscription. SMTP delivery can repeat after a crash. |
| `WeeklyDigestEmailListener.on` | Checks the requested destination, expiry and subscription. SMTP delivery can repeat after a crash. |
| `WorkspaceAlertEmailListener.on` | Checks that the attention change is current and the recipient subscribed. SMTP delivery can repeat after a crash. |

## Proofs

`DeliveryInboxIntegrationTest` redelivers after commit, rolls back a partial step, reorders remaining items and runs concurrent dispatch threads against PostgreSQL.
It also checks workspace isolation, instance keys, stream replacement, workspace purge, cascade deletion and a longer per-stream retention override.
`OutlineDocumentSyncIntegrationTest` stops between mirror commit and event recording, then proves that retry completes the event without another mirror write.

`IngestDurabilityIntegrationTest` pauses a private PostgreSQL container while real NATS redelivers a proven transient failure beyond the configured delivery count.
It checks that the second event waits and that a consumer restart preserves the failed head.
`ConsumerDeadLetterIntegrationTest` uses real NATS and PostgreSQL for capture, lost acknowledgement and termination, failed capture, permissions and replay.
It redelivers after a committed handler and exhausts the poison budget after a partial commit.
`SlackParticipantConsentFirewallIntegrationTest` resumes an edit after its base insert committed and checks that inbox steps contain no message or direct-message channel identifiers.
`IntegrationPoisonHandlerTest` covers classification through wrapped causes and the unknown-failure budget.
