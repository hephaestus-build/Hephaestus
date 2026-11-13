---
title: Webhook delivery and retry order
description: The provider event inbox's durability contract and its failure tests.
page_type: explanation
---

The receiver stores a verified delivery in `provider_event` in the request's transaction, and answers `202` after the commit.
The receiver requires UTF-8 JSON without raw NUL bytes, so person erasure can read every stored payload as PostgreSQL text.
Each stored row keeps the connection's exact source URL in `Hephaestus-Source-Server-Url`.
The receiver sets that header from the connection, and person erasure matches its registered source namespace.
It reads the matching connection routes through `ProviderEventSubscriptions` in the same transaction.
It then takes each connection's scoped lifecycle lock and reads its current state again.
Only an `ACTIVE` connection can admit a workspace row.
A concurrent suspension commits before this check or waits until the inbox insert commits.
A refused connection adds no payload or dedupe row. Its refusal reason is recorded in `webhook.rejected` and the admission log.
GitHub App deliveries match the installation ID from the signed body. A request header cannot set that ID.
The source App connection must be ACTIVE, including when the event also matches a PAT workspace.
A GitHub work event without a signed installation ID is dropped with `work-event-without-source`.
Admission locks the source and recipient connections in connection-ID order across workspaces.
GitLab and Outline deliveries must also match their connection's verified route.
If the connection configuration changed while admission waited, the receiver fails the request so the provider can retry against the current route.
The receiver stores one row for each admitted workspace.
GitHub installation, installation-repositories and installation-target control events get one row with a NULL workspace, because their handlers can create or repair a connection.
GitHub signs the body, not its event-type header. The adapter validates the control payload before it can enter the system scope.
A signed work payload relabeled as a control event is refused with `invalid-event-route`.
A work event without an organization or repository does not fall back into that system scope. Its payload is dropped.
A subscription change is visible to every delivery that the receiver stores after the change commits.
No consumer has to start or attach first, so a new repository or connection misses no event.

The unique index `uq_provider_event_delivery` is (workspace, provider, delivery key) with NULLS NOT DISTINCT.
`INSERT … ON CONFLICT DO NOTHING` makes a provider's retry or redelivery add no row.
`SubjectKeyDeriver` owns the key of each provider.
A handled row stays for `hephaestus.integration.consumer.replay-window` (default `3d`), the time in which GitHub can redeliver.
The commit wakes the claimers of the receiving server through `ProviderEventNotifications`. Claimers of other servers find the row at their next poll, every 5 seconds.
The inbox opens no `LISTEN` connection, so the chart's connection budget stays as it is.

## Claim, lease and order

Every installation control event shares one system `scope_key`, including a subject that names an organization.
Other rows use their workspace and their subject without the event token.
One scope is one resource of one workspace, such as a repository or a Slack channel.
Order is serial per scope, in row id order, not provider event time.
All installation events share one scope.

`ProviderEventRepository.claim` leases the oldest due QUEUED row:

- of an ACTIVE workspace, or of the installation;
- whose scope has no LEASED row;
- whose scope has no older QUEUED row.

The row lock is `FOR UPDATE OF e SKIP LOCKED` inside the CTE, so concurrent claimers on every server pass over a locked row.
The scope check reads the statement snapshot.
A claimer therefore sees an older QUEUED row of its scope that another claimer locked but did not commit yet, and skips the scope.
The partial unique index `uq_provider_event_leased_scope` (scope_key) WHERE state = 'LEASED' is the database backstop for one leased row per scope.
A failed row returns to QUEUED with its retry time and stays the oldest row of its scope, so later rows of the scope wait for it.

The claim increments `attempt` and `lease_epoch` and sets `leased_until` from `clock_timestamp()`.
Every later write of the claimer names the epoch: `complete`, `retry`, `dead`, `renew` and `release` change nothing under a newer lease.
The lease is 2 minutes. The claimer renews it every 30 seconds while its handler runs, up to `handler-timeout` (default `30m`).
The reaper takes back an expired lease every 30 seconds on each server.
A row that used `poison.max-attempts` becomes DEAD with `LeaseExpired`, so an event that stops every server cannot stop them in turn.
A stopping server releases its unfinished leases without counting the attempt.

A lease row, not an advisory lock, holds the scope.
A handler runs outside a database transaction for up to its timeout.
A session advisory lock would pin a pooled connection for that time and end with the session, not at a fenced expiry.

## Retries and dead letters

Database and provider transport failures retry with bounded exponential delay (`poison.base-delay` 2 s to `poison.max-delay` 5 min).
They do not count against the unknown-failure budget.
The inbox uses `SKIP LOCKED` for workspace and event-row fences and distinguishes a busy event row from a deleted row.
A renewal or a workspace operation can briefly hold a required row. Person erasure can also hold the copy fence. The handler rolls back and the inbox retries it in the same order without using the failure budget.
This expected contention has outcome `retried` and increments `integration_consumer_retry_total`.
The inbox logs it at DEBUG. Provider fallbacks rethrow a rejected inbox write before logging or trying another source.
A missing row or a changed lease still drops the work through the erasure or epoch fence.
Unknown exceptions retry up to `hephaestus.integration.consumer.poison.max-attempts` (default 10), then the row becomes DEAD.
Only a `PayloadParsingException` in the cause chain proves a permanent failure and makes the row DEAD on its first attempt.
Stack overflows use the unknown-failure budget.
Sanitized cause types and stack frames are logged on the first attempt and when a row becomes DEAD.

A DEAD row keeps its payload, headers, subject and arrival time, and records its error class and end time.
It stores no exception message or routing credential.
It stays for `hephaestus.integration.consumer.dead-retention` (default `30d`).
Workspace deletion cascades to its rows. Installation rows use NULL and separate instance-admin queries.
All workspace list and replay queries bind the workspace.

Replay sets a DEAD row of the workspace back to QUEUED with attempt 0, and records the account and time.
The row keeps its id, so it is at the head of its scope again, and its committed steps stay.
Replay is an explicit late event. It does not rewind later events.
[The stored work only moves forward](./sync-lifecycle.md#a-redelivered-or-older-payload-changes-nothing) defines provider versions and equal timestamps.
An installation replay commits its audit record in the replay transaction.
Operators follow [Replay a failed webhook event](../admin/webhook-replay).

Synchronous dispatch carries the workspace of the row.
GitHub repository and team handlers use that workspace.
A related issue uses it only when the workspace also monitors the related repository.
The dispatch scope ends when handling returns or throws and does not cross to asynchronous work.

## The database inbox

`provider_event_step` (event id, step name) records each committed handler step.
It holds no person data, and the event's deletion cascades to its steps.

`DeliveryInbox` binds the event id on the thread that executes dispatch and removes it in `finally`.
`DeliveryInbox.claim` inserts a named step with `ON CONFLICT DO NOTHING` inside the business transaction.
A conflict skips that step's writes.
A retry or replay of an event therefore skips its committed steps and resumes the unfinished ones.
A single-step event uses a fixed operation name, without message or direct-message channel identifiers.
A loop names each step by a stable item id, such as repository id and commit SHA, rather than the loop position.
Keep step names stable across releases while their events can stay in the inbox.
Calls from a sync job have no event and follow their usual natural-key and version rules.
A read can reconstruct the context that a later step needs after a committed step is skipped.
Failures still reach the retry and poison budget, also when an earlier step committed.

Orchestration handlers read providers outside their write transactions.
`GitHubInstallationMessageHandler` still verifies installation status inside its default handler transaction.
That call holds the transaction until the status read returns.
A process can stop after an HTTP or SMTP side effect and before its database completion record.
Such an external effect can repeat unless its adapter has a provider idempotency key or a durable claim-and-reconcile protocol.
The inbox guarantees database steps; it does not make a remote service part of a PostgreSQL transaction.

Person erasure deletes matching events in every state, including LEASED, and cascades their step records.
The inbox is a live-predicate erasure store: its preview counts can change without invalidating the frozen identity scope.
Each erasure pass deletes current matches as well as the frozen keys, and resume repeats the pass even after an earlier receipt.
Each pass covers deliveries stored before its inbox step. Webhook admission stays available during erasure.
Both inbox stores run after ordinary source erasure and before the final conversation copy scrub.
That scrub must stay last: its receipt also fences the tool results of turns that finish after erasure
([Personal-data map](/admin/dsms/personal-data-map#shared-source-content-and-hephaestus-tool-results)).
A named write step holds copy admission and a key-share row lock until commit. Renewal remains possible.
The reaper uses FOR UPDATE SKIP LOCKED, which skips that key-share lock.
Spring rechecks LEASED and the epoch with a shared row lock at commit in every write transaction.
A changed epoch rolls the whole transaction back, including its step record.
This covers independent natural-key upserts that have no named step.
A write that finds erasure in progress rolls back before waiting on its outcome, so domain locks cannot deadlock erasure.
After erasure, a missing event stops further writes and completion.
The consumer records `erased-during-processing` in `integration.message.dropped` and does not retry the event.
A replaced lease records `lease-lost` and cannot write under the new lease.
Renewal and reaping use SKIP LOCKED. They do not wait on a fenced write, and reaping leaves that write's lease alone.
Renewal continues until the outcome is recorded. Outcome checks hold no domain or global copy lock.
A committed handler step that deliberately erases its own event is successful handling.
A caught write-fence failure still reaches the consumer, which can retry the unfinished work.

Retention runs every hour on one server.
It deletes DONE rows after the replay window and DEAD rows after the dead retention.
A failure leaves the rows in place and increments `integration_consumer_inbox_retention_failures_total`.
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
An asynchronous listener or durable job is separate work; it does not inherit the dispatch thread's inbox identity.
Its existing natural keys, state transitions or delivery protocol own its idempotency.
The inbox fence covers Spring write transactions on the dispatch thread. It does not cover autocommit writes or another thread.
Provider handlers must delegate database writes to transactional services and must not send raw inbox envelopes to async jobs.
Architecture guards both boundaries. `CommitEnrichmentEventListener`, `SyncPushService` and Modulith jobs are separate work.
Their person-data contracts are recorded in the [personal-data map](/admin/dsms/personal-data-map).
Do not carry an inbox step into a later job by thread-local context.

## Other ingestion listeners

| Listener | Boundary and retry decision |
| --- | --- |
| `ScmItemEventRecorder`, `ScmRepositoryItemLifecycle`, `IntegrationAttentionService` and `ProviderTeardown.onActivated` | Synchronous or `BEFORE_COMMIT` writes join the publisher's transaction and inbox step. Item event identities also prevent duplicate occasions. |
| `ActivityEventListener` | Its `AFTER_COMMIT` methods open separate transactions and catch recording failures. The activity write is best-effort. Redelivery skips a committed handler step, so it cannot repair a missing activity row. |
| `CommitEnrichmentEventListener` | Async work has no inbox scope. It enriches commits without contributor rows; the next repository sync repairs a missed enrichment. |
| GitHub, GitLab, Slack and Outline connection-state listeners, `SlackDataSyncScheduler` and `OutlineCollectionAdminService.onCollectionResumed` | Start independent initialization, sync or teardown work after commit. Their sync, connection generation and provider-registration rules own repeated execution. |
| `ConnectionActivityRecorder` and `SyncPushService` | Invalidate local lookups or send UI refresh hints after commit. These effects do not write provider state. |
| `ActivityScmMirrorErasureListener` and `ItemEventEvidenceErasure` | Synchronous erasure joins its publisher's transaction. Repeated scoped deletes or evidence removal retain the erased state. |

Startup listeners and scheduled sweepers do not claim provider events.
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
| `MentorTurnCompletion.billTakenBack` | Each model call already has its ledger row, so a repeat bills nothing twice; the cost metric can repeat. |
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

`ProviderEventStoreIntegrationTest` stores a redelivery once, keeps a scope's order beside other scopes, holds a scope behind a failed row, and refuses a taken-back lease's outcome.
It also makes a reaped row DEAD after its attempts, replays a DEAD row with its payload and committed steps, and deletes ended rows after their window.
`DeliveryInboxIntegrationTest` retries after commit, rolls back a partial step, reorders remaining items and runs concurrent dispatch threads against PostgreSQL.
`GitLabWorkspaceEventRoutingIntegrationTest` receives over HTTP and waits until running claimers handle the rows of each subscribing workspace.
`OutlineDocumentSyncIntegrationTest` stops between mirror commit and event recording, then proves that retry completes the event without another mirror write.
`ConsumerDeadLetterIntegrationTest` covers the dead-letter API, paging, permissions, workspace isolation, replay, the installation audit and person-data erasure.
`SlackParticipantConsentFirewallIntegrationTest` resumes an edit after its base insert committed and checks that inbox steps contain no message or direct-message channel identifiers.
`IntegrationPoisonHandlerTest` covers classification through wrapped causes and the unknown-failure budget.
