---
title: Sync lifecycle
description: How repository activity is ingested, backfilled and kept current.
---

# Integration sync lifecycle

What each integration actually does at every phase of its life, from first connect to erasure.
Companion to [ADR 0024](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0024-integration-sync-lifecycle-and-two-deletion-semantics.md),
which records *why* the two deletion semantics are separate.

## The cohesive principle

Every integration consumes the vendor's own machine-readable contract, and Hephaestus hand-writes
only transport policy — retries, rate-limit accounting, pagination bounds, tenancy, consent.

| Integration | Contract | Generated from |
| --- | --- | --- |
| GitHub | GraphQL schema | GraphQL Codegen Gradle plugin → `…scm.github.graphql` |
| GitLab | GraphQL schema | GraphQL Codegen Gradle plugin → `…scm.gitlab.graphql` |
| Slack | Official SDK | `com.slack.api:bolt` |
| Outline | OpenAPI spec | OpenAPI Generator Gradle plugin → `spec3.yml` + `outline-supplement.yaml` |

`vp run schema:github`, `schema:gitlab`, and `schema:outline` refresh schemas and specs.
No integration hand-rolls a vendor DTO.

## The matrix

| Phase | GitHub | GitLab | Slack | Outline |
| --- | --- | --- | --- | --- |
| **Initial sync** | App install: after monitors materialize from installation metadata. PAT: on the `Activated` AFTER_COMMIT event. Recorded as an `INITIAL` job. | On `Activated` → `GitLabWorkspaceInitializationService.initializeAsync`: discover projects and monitors. Then start the NATS scope consumer, group webhook, and full sync, in that order. With NATS enabled, the webhook requires complete discovery and a confirmed consumer on connection subject filter `gitlab.?connection.<connectionId>.>`. Discovery must reach the end of the group listing and direct-project pass, and match GitLab's project count. Otherwise the periodic reconcile (or **Sync now**) retries it. A hook that is already open stays open. The connection consumer receives events even for projects not yet discovered. At delivery time, `GitLabRouteAdmission` reads an unknown project from GitLab. It checks the stable ID and connected-group membership. Only when repository selection permits it does it create or reconcile the monitor. `INITIAL` job. | **None at connect.** Ingestion is per-channel and forward-only from the consent announcement. | On `Activated` → register the webhook subscription, then a recency sync. Recorded job. |
| **Live updates** | `POST /webhooks/github` → `github.<owner>.<repo>.<event>`. Repository-lifecycle events ride `github.<owner>.?.repository`. | `POST /webhooks/gitlab/connections/{connectionId}/{keyId}/{routeId}` for group hooks Hephaestus registers on connect. `X-Gitlab-Token` carries a `GitLabRouteCredential` signed with `WEBHOOK_ROUTING_SECRET` and bound to that connection, key and route. The receiver rejects a supplied `X-Gitlab-Instance` that differs from the signed origin. It does not use that header to select a workspace. `GitLabSubjectKeyDeriver.deriveConnectionSubject` publishes `gitlab.?connection.<connectionId>.<event>`, independent of payload paths. Admission checks the active connection, workspace, configured GitLab instance and group before handling. GitLab has no shared endpoint. | Events API → `slack.<team>.<scope>.<event>`. Consent re-checked per message. | Vendor webhook subscription → `outline.<subscriptionId>.<event>`. |
| **Periodic reconcile** | `hephaestus.sync.cron`, default daily 03:00. Per-repository cooldown. | Same cron and scheduler shape. | `hephaestus.sync.slack.cron`, default daily 04:00 — replays `conversations.history` for ACTIVE channels. | `…outline.sync.cron`, default every 6 h, plus a 5-minute catch-up tick for collections still awaiting a clean pass. |
| **Backfill** | Supported. Scheduled cycle gated by `hephaestus.sync.backfill.enabled` (off by default). Manual backfill always offered and loops until complete or canceled. | Supported. One batch per pending repository per click. The scheduled cycle drains the rest at its 5-minute cooldown. | **Not supported**, deliberately: pre-consent and paused-gap history must never be fetched. | **Not supported.** The reconcile is a full enumeration. There is no older horizon to walk. |
| **Upstream deletion** | `RECONCILIATION` sweep tombstones issues/PRs (fail-closed). `repository.deleted` webhook removes repo + monitors. | `RECONCILIATION` sweep tombstones issues/MRs and removes deleted issue/MR notes, including diff notes. Each parent requires a complete REST note listing with consistent pagination counts. Incomplete parents remove nothing. GitLab sends no issue/MR or note-deletion webhook. | **No inference from absence.** `message_deleted` → tombstone. `channel_deleted` → erase our copy. `channel_archive` / `channel_left` → PAUSED. | `RECONCILIATION` only: a **clean** full enumeration tombstones that collection's vanished documents (fail-closed). `documents.delete` / `documents.permanent_delete` tombstone immediately. |
| **Erasure on disconnect / purge** | `ScmWorkspaceContentEraser` — hard delete, orphan-guarded. | Same eraser. Disconnect is GitLab's **only** erase trigger (no vendor uninstall signal). | `SlackWorkspaceContentEraser` — hard delete of messages, threads, monitored channels and consent. It also erases the content of every Hephaestus entry written in Slack, stops their running turns, and clears the Slack identity of conversations. | Hard delete of `outline_document` and `outline_collection` + webhook deregistration. |
| **Rename / transfer healing** | Real time within the same owner: the mirrored row and **every** monitor are re-keyed by the stable `repository.id`. Cross-owner transfer heals on the next reconcile. | A project rename or move inside the connected group arrives on the connection subject. Admission re-keys the project by native ID. A move out of the group heals on the next reconcile. | Channel rename handled on the consent/monitored-channel record. | Documents are keyed by `documentId`. A title or collection rename is ordinary metadata. |

## The rules the matrix depends on

### Partial metadata updates preserve known values

An update can omit counts and discussion-lock state. On an existing issue, pull request or merge
request, an absent value preserves the stored value. An explicit zero count or unlocked state
replaces it. Insert defaults apply only to new rows.

GitLab merge request sync treats an absent or failed count or lock field as unknown. Each field is
checked at its own GraphQL error path, so an error in one field does not discard valid sibling
values. An unknown note count cannot skip discussion sync.

### Fail-closed: an incomplete listing deletes nothing

A sweep may tombstone only what a **provably complete** upstream listing omits.
`UpstreamListing.complete()` starts `false` and becomes true only on one clean exit.
Pagination must reach `hasNextPage == false`.
There must be no rate-limit abort, GraphQL error, exception, page-cap truncation, or cancellation.
The node count must equal the server's own `totalCount`.

Any doubt skips the entity class entirely. A partial listing is never merged with a
previous one. Outline's equivalent: a truncated enumeration throws rather than returning a short
list, and a budget-exhausted pass tombstones nothing.

GitLab note reconciliation uses the flat REST notes endpoint for both issues and merge requests,
including diff notes. It walks every live mirrored parent, not only recently updated work. It requires
explicit `X-Page`, `X-Next-Page`, and an unchanged `X-Total`, and the unique note count must equal that
total. Missing headers, duplicate IDs, a changed count, failed pages, cancellation, or the page cap
leave that parent's notes untouched. GitLab can omit count headers for large listings. Those parents
are not reconciled.

The reconciler captures candidates before listing, so notes inserted during the pass survive.
Every missing candidate must also return 404 on a direct note read: offset pages can shift without changing their
total. A successful read or any failed confirmation keeps that parent unchanged.

The reconciler removes deleted review notes with their reply references. It also removes empty mirrored threads.
Future evidence capture and Hephaestus reads no longer include them. Previously captured review snapshots
remain immutable under their existing retention policy.

### Reconciliation-only: an `INITIAL` job never infers a deletion

`INITIAL` jobs never sweep.
A mirror still under population has nothing stale in it.
A set difference would treat every row not yet fetched as an upstream deletion. For the same reason, the API rejects a manual `INITIAL`. Only `RECONCILIATION` and `BACKFILL` are client-triggerable.

This holds **uniformly across every integration that infers deletion from absence**, not just the SCM
pair. Each runner forwards its `SyncJobType` rather than dropping it, and the inference sits behind a
`type == RECONCILIATION` gate: `GitHubDataSyncScheduler` / `GitLabDataSyncScheduler` before the
deletion sweep, and `OutlineDocumentSyncService#syncOneCollection` before `tombstoneVanished`. Slack
needs no gate — it never infers deletion from absence at all (deletions arrive as `message_deleted` /
`channel_deleted` events), so its runner genuinely does not use `type`.

The two guards are independent and both must hold before anything is tombstoned: the enumeration must
be provably **complete**, *and* the job must be a **`RECONCILIATION`**. A budget-exhausted or
truncated Outline pass therefore deletes nothing even on a reconcile.

### Tombstone ≠ erasure

For issues and merge requests, two operations have distinct triggers and deliberately share no code.

| | Issue and merge request drift tombstone | Mirror erasure |
| --- | --- | --- |
| Cause | A reconcile pass observes an item missing upstream | Admin disconnect, or workspace purge |
| Effect | `deleted_at` marker. Content-bearing fields cleared | Hard `DELETE`, including of tombstoned rows |
| Reversible | Yes — `upsertCore` clears `deleted_at` on the next sync | No |
| Basis | Sync fidelity | The lawful basis for holding the mirror is gone |

A tombstoned row is still queryable retained personal data, so a tombstone can never implement the
disconnect trigger. A hard delete destroys data that the drift sweep expects to restore.
Thus, erasure can never implement the issue or merge request drift path. Notes have no tombstone model:
the per-parent reconciliation described above removes their mirror rows instead.

There is no entity-level soft-delete filter.
Each surface decides whether reads honor a tombstone or keep returning the row as a historical record.
See
[ADR 0024](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0024-integration-sync-lifecycle-and-two-deletion-semantics.md)
under *Which reads honor a drift tombstone*.

### Erasure is orphan-guarded

SCM tables carry no `workspace_id`: `repository` and its cascade are instance-global and shared
between workspaces monitoring the same source repository. Thus, `ScmWorkspaceContentEraser` removes *this* workspace's `repository_to_monitor` rows, then flushes.
It delegates each repository to `WorkspaceRepositoryMonitorService#deleteRepositoryIfOrphaned`.
**Only** if no monitor anywhere points to the repository, this method deletes the local clone.
It then publishes `RepositoryAboutToBeDeletedEvent` and drops the row.

Reads scope SCM work to a workspace the same way: through its `repository_to_monitor` rows.
A monitor stores the repository name, but a name is unique only per provider.
So a monitor covers a repository only when `MonitoredRepositoryScope` also agrees.
The monitor's native id, once known, must be the repository's.

The repository's provider must be the instance registered for the workspace's SCM connection.
Its kind and server URL must match.
When no URL is configured, the registered default URL for that kind applies.
Only active or suspended SCM connections authorize this scope.
A pause preserves retained-source reads.
Pending and uninstalled connections authorize none.

Without an eligible SCM connection, the monitor covers no repository.
Repository removal captures the exact covered repository IDs before removing the monitor.
It then applies the same scope to check whether another workspace still monitors each repository.

The org tier (`team`, `team_membership`, `organization_membership`) has its own guard.
No repository cascade reaches it.
Erasure occurs only when no other non-purged workspace binds to the same `Organization`. The `Organization` row itself is global: unlinked, never deleted.

Derived rows go first, while the items they reference still exist: the eraser publishes a
synchronous in-transaction `ScmMirrorErasedEvent`, and `practices` (SCM item results and
feedback) and `activity` (`activity_event`) listen.

Disconnect and purge call the same eraser, so they reach the same end state by construction.
The purge contributor uses order `-200`.
Disconnect calls the GitHub or GitLab strategy's `eraseLocalData` inside its transaction.

### Retained on both erase triggers

`sync_job`, `connection_activity` and `connection_audit` survive for all four integration kinds:
operational audit only (kind, type, status, timestamps), no mirrored third-party content, capped per
connection by the sync-job pruner. Global identity rows (`user`, `organization`,
`identity_provider`) are cross-tenant shared and never touched here.

### A redelivered or older payload changes nothing

A provider can deliver a webhook again, even hours later.
JetStream drops a duplicate only inside its deduplication window.
Two rules make a redelivery harmless.
Hephaestus keeps no record of processed delivery ids.

**The stored work only moves forward.**
Each consumer compares the payload with the stored row.
Pull and merge requests, issues and comments are read with a row lock.
For these, the compare and the write are one step, also when a sync writes the same row.

- A payload with an older `updated_at` than the stored row changes nothing.
  This rule applies to pull and merge requests, issues, comments, reviews, review comments, notes, diff notes and Outline documents.
- A Slack edit applies only if its `edited.ts` is later than the stored edit.
- A Slack change without `edited.ts` is a new reply or an unfurl. It changes nothing.
- A GitHub dismissal is final.
  A second dismissal publishes nothing.

**An event records its item event once.**
A lifecycle event has one identity, its source key.
`ScmEventIdentity` derives it, and the webhook, a late delivery, a redelivery and the sync derive the same key for the same change:

- A push is the move from one head commit to the next.
  A force-push back to an earlier head is a new move.
- A ready, draft or reopen is the change and the moment that the provider dates.
- A close or a merge is the stored `closedAt` or `mergedAt`, which the webhook and the sync store alike.
- An edit of a comment is its `updated_at` and its text, so two edits in one second are two edits.
- A dismissal is the `updated_at` that the review stores for it.
  GitLab can dismiss a renewed approval of one review again.
- A thread resolution is its `resolvedAt`.

The unique key on item, name and source key absorbs a second report of the same event, whatever version the work has by then.
The unique key on item, name and version still absorbs a second event of the same name for the same work.
The activity entry of the event uses the same source key, so each path adds one entry.
The activity entry of a close or a merge keeps its time key, which is the stored `closedAt` or `mergedAt`.
That key identifies the same moment on every path, and it matches the entries recorded before source keys existed.
A sync reports a close or a merge only when it stores one, so a sync that reads a closed issue again adds nothing.

A close, reopen, ready or draft event publishes when the stored state is the payload's state.
It publishes also when its payload is older than the stored row.
Thus, a first delivery that arrives after a later change still records its item event, for the current work.
A redelivery of it records nothing.

A GitLab sync records the ready, draft and push changes that it stores, under the same source key as their hook.
Thus, a hook that arrives after the sync records nothing new, and a lost hook loses no review.
GitLab offers a merge for review after the read that follows the hook.
GitHub redelivers a failed delivery, so its sync records no push or ready for a stored pull request.

Provider dates have second precision.
A push in the same second as the stored change is applied only if it continues from the stored head.
An older push in that second records its own item event key and never moves the head back.
An item event recorded before the source key existed has no key.
A redelivery of such an event can record a second item event if the work moved since.

### Reviewer lists are dated snapshots

A pull or merge request's review requests arrive whole from webhooks and sync.
They include the people asked, GitLab's per-reviewer review state, and GitHub's teams. A payload's `updated_at` can rule out an older payload.
It cannot order two payloads within the same second.
GitHub asks each reviewer in a
[`review_requested` event](https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request)
of its own, often within one second.
An approval's GitLab hook can carry `updated_at` from before the approval (below).

Thus, each list records when Hephaestus received it.
A list received earlier than the stored list changes nothing.
A backlogged webhook or old sync page cannot remove, re-add, or restate a request. A list received at the same instant as the stored one applies, so a
redelivery restates what it said.

A writer reads the pull request with its row locked before it compares
(`PullRequestRepository.findForUpdateByRepositoryIdAndNumber`).

An incomplete list is not applied.
`GetMergeRequestReviewers` completes GitLab's nested `reviewers` connection.
If GitHub counts more entries, its single `reviewRequests(first: 100)` page remains unused.

The two dates come from two clocks. A webhook's is the time JetStream stored it, on the NATS server's
clock, and a JetStream redelivery keeps it. A sync page's is the time Hephaestus asked for it, on the
application's clock. Comparing them assumes both hosts keep time by NTP, so their skew is far below
the seconds between a reviewer change and the next read.

Two gaps remain. A provider that delivers a
webhook late, or retries a failed delivery, dates it by its arrival, not by when the provider sent
it. And a provider's redelivery that reaches the receiver after `hephaestus.webhook.stream.duplicate-window`
(3 hours by default) is a new message with a new date. Either can restate an old list over a sync
page read in between, until the next sync reads the list again.
`GitHubWebhookRedelivery` asks GitHub to redeliver failed deliveries for up to two hours after the original.
Thus, after a broker stall, a late copy is a normal event, not a rare one.

GitLab's webhooks state each reviewer's review from 18.6
([`reviewers_hook_attrs`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.6.0-ee/app/models/concerns/issuable.rb#L589-597)). Before, they name
the reviewers only ([`issuable.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/lib/gitlab/data_builder/issuable.rb#L31)). A reviewer named without a state keeps the stored state.
Thus, before GitLab 18.6, webhooks determine who is asked, and sync determines each review's state. A state Hephaestus does not know clears the
stored one, so a newer GitLab's states cannot leave an old verdict standing. From 18.6 a re-request runs a merge request
hook of its own
([`request_review_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.6.0-ee/app/services/merge_requests/request_review_service.rb#L10-28)),
and from 19.3 so does a submitted review
([`update_reviewer_state_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v19.3.0-ee/app/services/merge_requests/update_reviewer_state_service.rb#L67-75)).

On GitLab, each verdict change, submitted review, and re-request writes a system note on the merge request.
These notes cover approval, approval withdrawal, requests for changes, and submitted reviews
([`system_notes/merge_requests_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/system_notes/merge_requests_service.rb#L172-194)),
and re-requesting a review
([`request_review_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/merge_requests/request_review_service.rb#L18)).
Starting a review writes none, which is harmless: a started review is still requested, as an
unreviewed one is.

Saving a note touches its merge request
([`note.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/models/note.rb#L210), `after_save :touch_noteable`).
Thus, the merge request's `updatedAt` advances.
The incremental sync's `updatedAfter` watermark reads the new state even if it missed the webhook.

The approval's webhook can carry the old `updated_at`.
GitLab writes the approval note and runs approval hooks in separate background jobs
([`event_store.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/lib/gitlab/event_store.rb#L52-54)).
Thus, GitLab can build the hook before the note touches the merge request.

On GitHub, a review request or removal advances the pull request's `updatedAt`.
The recorded `pull_request.review_requested` and `review_request_removed` fixtures show this.
Thus, incremental sync reads a missed event.

### A GitLab merge request's review decision comes from its reviewers

GitLab's `approved` says only that the approval rules are met
([`approval_state.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/models/approval_state.rb)), and a
project that requires no approval meets them with nobody approving. So the sync
(`GitLabMergeRequestProcessor#reviewDecision`) derives the decision from what people did:

- `CHANGES_REQUESTED` while a request for changes stands.
  On Premium, this includes `REQUESTED_CHANGES` merge status when the project blocks merge for a request.
  On every tier, it includes any reviewer's `REQUESTED_CHANGES` review state.
- `APPROVED` when someone approved and `approved` is true.
- `REVIEW_REQUIRED` otherwise, including an approval that leaves required approvals missing.
- None when the reviewer or approver list was not read whole: either could hide a decision.

The processor does not read `approvalsRequired`, because the Community Edition schema has no such field. A request for changes is
attributed to a person only from the system note "requested changes" that names them
(`GitLabReviewReconciler#recordSystemNote`). The `detailed_merge_status` embedded in a note hook belongs to the merge
request and is repeated on every note while anyone's request stands. A live note of that kind also dismisses its
author's approval, because GitLab withdraws a reviewer's approval without a note or hook of its own
([`update_reviewer_state_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/merge_requests/update_reviewer_state_service.rb)).

A webhook names one person's act, not the whole decision:

- `approved`/`approval` and `unapproved`/`unapproval` differ only in whether the rules were met afterwards
  ([`execute_approval_hooks_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/ee/merge_requests/execute_approval_hooks_service.rb),
  [`remove_approval_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/ee/merge_requests/remove_approval_service.rb)).
- An accepted hook or live system note can change someone's review state (see the gate below).
  It then leaves the stored decision unknown, together with approval-derived mergeability and merge status.
  These stay unknown until the post-hook readiness read (below) or sync reads the merge request again. A read that fails leaves them unknown. A hook
  the gate rejects changes none of them. The note touches the merge request,
  so the incremental sync does read it.
- A redelivery changes no one's review, so it leaves a decision the sync has since read in place.
- An approval act or GitLab reset applies only if its `updated_at` is not older than stored and its head matches.
  Arrival time does not change this requirement.
  A hook for an older version or another head changes no stored review facts.
  An equal version applies.
  This can reject a legitimate approval hook built before its note advanced `updated_at` (above).
  After storage of the newer version, the post-event readiness read or next sync records that approval from the approver list.
  Until a read succeeds, the decision remains as the newer event left it.
  An act received before a stored approval read belongs to that read (the dated snapshot above).
  Acts from two people received at the same instant both apply.
- GitLab resetting approvals after a push sends `unapproved`/`unapproval` marked `system`, with `system_action`
  `approvals_reset_on_push` (all of them) or `code_owner_approvals_reset_on_push` (Code Owners' only)
  ([system-started events](https://docs.gitlab.com/user/project/integrations/webhook_events/#system-initiated-merge-request-events)).
  Its user is the person who pushed.
  It does not identify whose approvals disappeared.
  Thus, it leaves the decision unknown and dismisses no one. The readiness read reconciles the approvals with GitLab's whole approver list.
- A push leaves the decision, mergeability and merge status unknown for the new head. The recorded approvals stay, since
  a project can keep them across a push. A GitLab approval's stored commit is its recorded association, not proof of
  the originally approved commit.

### A GitLab merge request's readiness is read after its webhook

A merge request hook carries no merge status, head pipeline, or approvals, which merge advice requires.
After storage of an opened, updated, reopened, or approval hook, `GitLabMergeRequestMessageHandler` reads that merge request outside the hook transaction.
It uses `GetMergeRequestReadiness` and `GitLabMergeRequestReadinessReader`.
`GitLabMergeRequestProcessor#applyReadiness` records the result in a second short transaction, with the row locked.

The delivery must still have permission to write to the project as currently stored (`GitLabWebhookContextResolver#mayStillWrite`).
On a connection route, the connection remains active and the handler admits the project again.
It checks the same GitLab instance, group membership, and continued monitoring.
A project removed from monitoring or moved out during the GitLab read takes nothing from it.
Outside a connection route, the project must still pass the same workspace's scope filter.

Mutable readiness requires GitLab to name the same project and merge request, both open or both merged.
The head must match the stored head, and the version must not be older.
The read never moves the head.
Reviewers, decision, approvals, and approval-derived mergeability and merge status follow the dated snapshot, as for a sync page.
The date is when the read was requested.
A read or page begun before storage of a newer one changes none of these fields.

While GitLab reports `checking` or `approvals_syncing`, mergeability and approvals remain unsettled and unknown.
A failed read records nothing.
The next hook or sync reads again.
A hook never runs a workspace sync.

A merge hook stores its named merge commit, but often has no merger or merge time.
The same post-hook read fills those fields where GitLab supplies them (`applyTerminalFacts`).
The same identity and head fence applies, and it never replaces a recorded merger.
These completed merge facts do not replace mutable content.
They do not require GraphQL's second-precision version to equal the webhook's millisecond version.

The same short transaction offers the merge event only after the read, even if the read failed.
A merge missed by the hook but found by sync becomes a sync-discovered event.
A later hook delivery can still claim it.

For a merged merge request, the native approvals route can supply each standing row's creation time as `approved_at`
([GitLab approvals API](https://docs.gitlab.com/api/merge_request_approvals/)). It does not identify the originally
approved commit or provide an atomic approval history. A whole accepted approval snapshot matches the native row set
before recording those dates. A failed optional date read preserves an already-standing date, while an accepted absent
or conflicting date leaves it unknown. Renewal clears a withdrawn row's date.

When GraphQL's version is older, only dates of existing standing approvals can be reconciled.
The native response must name the same merged request and exact stored version, with matching non-null title and description.
Its actor set must match both GraphQL's whole current-head approvers and the canonical standing rows.
Otherwise, the read cannot change those dates.

An accepted date-only read advances the existing approval-read clock, even when the date stays unchanged.
It changes no membership, readiness, or commit, and emits no approval act.
This also orders overlapping date-only reads.

GitLab answers an unresolved field with `null` and an error at that path.
Every GitLab merge request read checks errors at, above, or below the exact field through Spring.
This applies to this read, sync, and historical backfill.
It uses `ClientResponseField#getErrors`, with `nodes[i]` on a page.
Thus, a failed field is unknown, never a value:

- A failed `approved` is not a refusal.
- A failed approver list is not an empty list.
- A failed `headPipeline`, status, or SHA records no check state.

Only `headPipeline: null` without an error means GitLab reports no pipeline.
Without a captured `updatedAt`, a page changes nothing stored about the merge request.
Without a captured `diffHeadSha`, it records no head-specific decision, mergeability, approvals, or missing pipeline.
The stored head remains, but it is not the head the page read.
A pipeline that the page names with its SHA is still recorded for that SHA.

### Head checks are dated observations

`CheckState` distinguishes `NO_PIPELINE` and `SKIPPED` from `NONE`.
`NO_PIPELINE` means GitLab reports no pipeline for the head, not whether CI is configured.
`SKIPPED` means a skipped pipeline.
`NONE` means GitHub's empty rollup or either of the other states on earlier stored GitLab records.

A GitLab check observation records its request or send time at the provider (`head_check_observed_at`).
Sync and readiness reads use their request time.
Pipeline hooks use the time JetStream stored them.
An observation older than the stored observation changes nothing, regardless of either observation's commit.
Thus, an earlier read or delayed hook cannot restore a state GitLab has since replaced.

A later observation replaces the state outright, including a pipeline that passes on retry.
A record of unknown age accepts any dated observation.
GitHub's check paths remain undated and unchanged.

### Check runs of the head

A pull request also stores the single runs behind its check state (`head_check_runs`), with the commit that they are about.

- **GitHub.** A check suite of the head completes, whatever it concluded, or a commit status of the head ends.
  Then the server reads the check runs, commit statuses and check suites of that commit (`GetCommitCheckRuns`).
  It reads one page of 100 runs and asks only for what the Checks and Commit statuses permissions grant.
  The list is complete only when nothing GitHub knows of is still to come.
  It fits one page, and the rollup is neither pending nor expected.
  Every listed run has ended, and every check suite with runs has completed.
  A failed rollup is no such sign, because GitHub reports a failure while other runs still go.
  A suite without runs is left aside, because an app that never posts a run leaves it queued.
  A CI that has posted nothing yet cannot be seen, so a later workflow or status can still add runs.
  An incomplete list never records that the checks finished.
  A suite can wait for a maintainer to approve its workflows, as for a pull request from a fork.
  Such a suite makes the list incomplete with the limitation `AWAITING_APPROVAL`.
  GitHub completes such a suite as `action_required` with no runs, and its rollup can still read success.
  Its check state is pending, not failed.
  The suite and runs of Hephaestus's own GitHub App (`hephaestus.integration.github.app.id`) are left out of the list.
  The server also ignores a check suite event of its own app, so that suite never sets the check state.
- **GitLab.** Not captured yet.
  A trigger job reports success when it creates its child pipeline, not when that pipeline ends.
  Each pipeline hook of a commit lists only its own pipeline.
  Thus, no hook shows which runs of the commit finished.

Scheduled sync reads the runs again for each open pull request whose stored list of its head has not finished.
It does so only for a list whose runs last changed in the last 24 hours.
A failed read records nothing, and the stored runs stay.
A read that is older than the stored read of the same commit changes nothing.
A read that shows the same runs keeps the stored read and its time.
The server stores at most 200 runs, cuts names to 200 characters and keeps links without user information or a query.

When the stored runs change and every run of the head has ended, the server records the item event `scm.pull_request.checks_completed`.
The event pins the same item version as the push, because the runs are not part of the version.

The event's source key names the settled runs: the item, the head and a digest of the runs.
The digest covers each run's name, group, state and end time.
A repeated read of the same runs records no second event.
A read that shows them in another order, or that lags and shows earlier runs again, records none either.

A re-run on the same head ends at another time, so it records a new event at the same version.
This is also true when the results match an earlier settling, as for a build that fails, passes and fails again.
The practices that select **Checks finished** then review it again.
Its readiness edge also selects each practice that waits for the checks of the item ([readiness reasons](practice-review-glossary#readiness-reasons)).
Every other item event keeps one event of a name per version.

Each read that records no completion logs why: the list is incomplete, a run has not finished, or the head has no rollup.
Between scheduled syncs, the server reads again only when another suite or status of the head ends.
Thus, a read that lags the last hook records a completion at the latest with the next scheduled sync.
Checks that never finish, and a head without checks, record none.
A practice that must review every push also selects **Opened**, **Ready** or **Synchronized**.

## Documented asymmetries and residuals

- **Slack has no deletion *sweep* at all.** Its content model is append-plus-watermark.
  A message absent from `conversations.history` usually indicates truncated pagination, a filtered subtype, or a thread reply.
  Deletion inferred from absence would cause mass deletion. Deletions arrive as events
  instead. Outline *does* infer deletion from absence, but not in a separate sweep phase.
  It tombstones inline after each collection's clean enumeration, under the same reconciliation-only rule as SCM sweeps.
- **Cross-owner GitHub transfer heals only on reconcile.** The event derives the *new* owner's
  subject and so reaches no filter of the old workspace. The next reconcile resolves the monitor by
  `native_id`. GitLab has the identical residual across root groups.
- **Legacy rows without `native_id`** fall back to a previous-`owner/name` lookup, which a
  simultaneous rename-and-transfer can miss. Those rows acquire a `native_id` on their next sync.
- **A reused repository name.** After a GitHub rename, a new repository can take the old name.
  GitHub webhooks select the repository row by `repository.id`, so they file events correctly.
  An earlier version selected it by name and could file an issue or a pull request under the wrong row.
  The next event or sync of that work moves the row to the repository that GitHub reports (`GitHubMisfiledWork`).
  A review or comment webhook also does this before it creates a stub for missing work.
  The work is skipped instead, and the rest of the sync continues, in two cases:
  - `reason=itemRegistered`: a workspace already registered an item for the work.
    An item's parent never changes (`guard_item_identity`), so a moved row would leave its item under the wrong repository.
  - `reason=idHeldByAnotherRepository`: the reported repository already has a row of the same type for the number.
    An example is a stub that a comment created.
  `github.misfiled.work{type,outcome,reason}` counts moves and skips.
  A review or comment on skipped work is dropped.
  A stub for it would collide with the held id.
  Each warning names the `rowId` in `issue`, its `holdingRepositoryId` and the reported `repositoryId`.
  No automatic repair exists for a skipped row. Its work stays under the holding repository until a maintainer moves the row and its items.
  GitLab webhooks still select a project by its path, so a reused GitLab path has the same hazard.
- **No replay across a filter rebuild.** Repository-scoped events published between a rename and the
  consumer-filter rebuild were never pending for the durable consumer.
  They are not redelivered. The
  next reconcile's full pass backfills them.
- **Slack paused gaps are never backfilled.** Resume of a PAUSED channel stamps the watermark to the resume instant.
  Messages sent while monitoring was off stay outside the mirror permanently.
- **Reconnect is a fresh start, not a restore.** Because disconnect erases, reconnecting re-fetches
  from the vendor and cannot recover what the vendor no longer has.

## Where the code lives

| Concern | Class |
| --- | --- |
| Job types and what each implies | `integration.core.sync.SyncJobType` |
| Per-integration job bodies | `…{github,gitlab,slack,outline}…IntegrationSyncRunner` |
| Deletion sweeps | `GitHubDeletionSweepService`, `GitLabDeletionSweepService`, `OutlineMirrorRetentionService#tombstoneVanished` |
| SCM erasure | `workspace.ScmWorkspaceContentEraser`, `workspace.adapter.ScmWorkspacePurgeAdapter` |
| Slack / Outline erasure | `SlackWorkspaceContentEraser`, `OutlineConnectionStrategy#eraseLocalData`, `OutlineWorkspacePurgeAdapter` |
| Monitor identity healing | `BackfillStateProvider#reconcileSyncTargetIdentity` / `#reconcileSyncTargetsForRepository` |
| Subject grammar | `…webhook.*SubjectKeyDeriver`, `integration.core.consumer.ConsumerSubjectMath` |
