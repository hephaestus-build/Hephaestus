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

Schemas and specs are refreshed by `vp run schema:github`, `schema:gitlab`, and `schema:outline`.
No integration hand-rolls a vendor DTO.

## The matrix

| Phase | GitHub | GitLab | Slack | Outline |
| --- | --- | --- | --- | --- |
| **Initial sync** | App install: after monitors materialise from installation metadata. PAT: on the `Activated` AFTER_COMMIT event. Recorded as an `INITIAL` job. | On `Activated` → `GitLabWorkspaceInitializationService.initializeAsync`: project discovery and monitors, then the NATS scope consumer, then the group webhook, then the full sync. With NATS enabled, the webhook opens only after a discovery that pages to the end of both the group listing and its direct-project pass and matches GitLab's project count, and a consumer confirmed on the connection subject filter `gitlab.?connection.<connectionId>.>`; otherwise the periodic reconcile (or **Sync now**) retries it. A hook that is already open stays open. The connection consumer receives events even for projects discovery has not listed: `GitLabRouteAdmission` reads an unknown project from GitLab at delivery time, checks its stable ID and connected-group membership, and creates or reconciles its monitor only when repository selection allows it. `INITIAL` job. | **None at connect.** Ingestion is per-channel and forward-only from the consent announcement. | On `Activated` → register the webhook subscription, then a recency sync. Recorded job. |
| **Live updates** | `POST /webhooks/github` → `github.<owner>.<repo>.<event>`; repository-lifecycle events ride `github.<owner>.?.repository`. | `POST /webhooks/gitlab/connections/{connectionId}/{keyId}/{routeId}` for group hooks Hephaestus registers on connect. `X-Gitlab-Token` carries a `GitLabRouteCredential` signed with `WEBHOOK_ROUTING_SECRET` and bound to that connection, key and route. The receiver rejects a supplied `X-Gitlab-Instance` that differs from the signed origin; it does not use that header to select a workspace. `GitlabSubjectKeyDeriver.deriveConnectionSubject` publishes `gitlab.?connection.<connectionId>.<event>`, independent of payload paths. Admission checks the active connection, workspace, configured GitLab instance and group before handling. Operator-created hooks still use the shared `POST /webhooks/gitlab` endpoint and its namespace subjects. | Events API → `slack.<team>.<scope>.<event>`; consent re-checked per message. | Vendor webhook subscription → `outline.<subscriptionId>.<event>`. |
| **Periodic reconcile** | `hephaestus.sync.cron`, default daily 03:00; per-repository cooldown. | Same cron and scheduler shape. | `hephaestus.sync.slack.cron`, default daily 04:00 — replays `conversations.history` for ACTIVE channels. | `…outline.sync.cron`, default every 6 h, plus a 5-minute catch-up tick for collections still awaiting a clean pass. |
| **Backfill** | Supported. Scheduled cycle gated by `hephaestus.sync.backfill.enabled` (off by default); manual backfill always offered and loops until complete or cancelled. | Supported. One batch per pending repository per click; the scheduled cycle drains the rest at its 5-minute cooldown. | **Not supported**, deliberately: pre-consent and paused-gap history must never be fetched. | **Not supported.** The reconcile is a full enumeration; there is no older horizon to walk. |
| **Upstream deletion** | `RECONCILIATION` sweep tombstones issues/PRs (fail-closed). `repository.deleted` webhook removes repo + monitors. | `RECONCILIATION` sweep tombstones issues/MRs and removes deleted issue/MR notes, including diff notes. Each parent requires a complete REST note listing with consistent pagination counts; incomplete parents remove nothing. GitLab sends no issue/MR or note-deletion webhook. | **No inference from absence.** `message_deleted` → tombstone; `channel_deleted` → erase our copy; `channel_archive` / `channel_left` → PAUSED. | `RECONCILIATION` only: a **clean** full enumeration tombstones that collection's vanished documents (fail-closed). `documents.delete` / `documents.permanent_delete` tombstone immediately. |
| **Erasure on disconnect / purge** | `ScmWorkspaceContentEraser` — hard delete, orphan-guarded. | Same eraser. Disconnect is GitLab's **only** erase trigger (no vendor uninstall signal). | `SlackWorkspaceContentEraser` — hard delete of messages, threads, monitored channels, consent, mentor threads. | Hard delete of `outline_document`, `outline_collection`, `outline_document_event` + webhook deregistration. |
| **Rename / transfer healing** | Real time within the same owner: the mirrored row and **every** monitor are re-keyed by the stable `repository.id`. Cross-owner transfer heals on the next reconcile. | Project rename/move rides the root-group tier and re-keys by native id; a move across root groups heals on the next reconcile. | Channel rename handled on the consent/monitored-channel record. | Documents are keyed by `documentId`; a title or collection rename is ordinary metadata. |

## The rules the matrix depends on

### Fail-closed: an incomplete listing deletes nothing

A sweep may tombstone only what a **provably complete** upstream listing omits.
`UpstreamListing.complete()` starts `false` and is set on exactly one clean exit: pagination ran to
`hasNextPage == false`, with no rate-limit abort, no GraphQL error, no exception, no page-cap
truncation, no cancellation, and a node count that agrees exactly with the server's own
`totalCount`. Any doubt skips the entity class entirely. A partial listing is never merged with a
previous one. Outline's equivalent: a truncated enumeration throws rather than returning a short
list, and a budget-exhausted pass tombstones nothing.

GitLab note reconciliation uses the flat REST notes endpoint for both issues and merge requests,
including diff notes. It walks every live mirrored parent, not only recently updated work. It requires
explicit `X-Page`, `X-Next-Page`, and an unchanged `X-Total`, and the unique note count must equal that
total. Missing headers, duplicate IDs, a changed count, failed pages, cancellation, or the page cap
leave that parent's notes untouched. GitLab can omit count headers for large listings; those parents
are not reconciled. Candidates are captured before listing, so notes inserted during the pass survive.
Every missing candidate must also return 404 on a direct note read: offset pages can shift without changing their
total. A successful read or any failed confirmation keeps that parent unchanged.
Deleted review notes are removed with their reply references; empty mirrored threads are removed too.
Future evidence capture and Heph reads no longer include them. Previously captured review snapshots
remain immutable under their existing retention policy.

### Reconciliation-only: an `INITIAL` job never infers a deletion

`INITIAL` jobs never sweep — a mirror still being populated has nothing stale in it, and every row
not yet fetched would look like an upstream deletion to a set difference. That is also why a manual
`INITIAL` is rejected at the API; only `RECONCILIATION` and `BACKFILL` are client-triggerable.

This holds **uniformly across every integration that infers deletion from absence**, not just the SCM
pair. Each runner forwards its `SyncJobType` rather than dropping it, and the inference sits behind a
`type == RECONCILIATION` gate: `GithubDataSyncScheduler` / `GitlabDataSyncScheduler` before the
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
| Trigger | A reconcile pass observes an artifact missing upstream | Admin disconnect, or workspace purge |
| Effect | `deleted_at` marker; content-bearing fields cleared | Hard `DELETE`, including of tombstoned rows |
| Reversible | Yes — `upsertCore` clears `deleted_at` on the next sync | No |
| Basis | Sync fidelity | The lawful basis for holding the mirror is gone |

A tombstoned row is still queryable retained personal data, so a tombstone can never implement the
disconnect trigger. A hard delete destroys data the drift sweep expects to be able to resurrect, so
erasure can never implement the issue or merge request drift path. Notes have no tombstone model:
the per-parent reconciliation described above removes their mirror rows instead.

There is no entity-level soft-delete filter: which reads honour a tombstone, and which keep returning
the row because they record something that happened, is decided surface by surface in
[ADR 0024](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0024-integration-sync-lifecycle-and-two-deletion-semantics.md)
under *Which reads honour a drift tombstone*.

### Erasure is orphan-guarded

SCM tables carry no `workspace_id`: `repository` and its cascade are instance-global and shared
between workspaces monitoring the same source repository. `ScmWorkspaceContentEraser` therefore
removes *this* workspace's `repository_to_monitor` rows, flushes, and delegates each repository to
`WorkspaceRepositoryMonitorService#deleteRepositoryIfOrphaned`, which deletes the local clone,
publishes `RepositoryAboutToBeDeletedEvent`, and drops the row **only** when no monitor anywhere
still points at it. The org tier (`team`, `team_membership`, `organization_membership`) has its own
guard — no repository cascade reaches it — and is erased only when no other non-purged workspace is
bound to the same `Organization`. The `Organization` row itself is global: unlinked, never deleted.

Derived rows go first, while the artifacts they reference still exist: the eraser publishes a
synchronous in-transaction `ScmMirrorErasedEvent`, and `practices` (SCM-artifact observations and
feedback) and `activity` (`activity_event`) listen.

Disconnect and purge reach the identical end state by construction — the purge contributor
(order `-200`) and the GitHub and GitLab strategies' `eraseLocalData`, which the disconnect runs
inside its transaction, both call the same eraser.

### Retained on both erase triggers

`sync_job`, `connection_activity` and `connection_audit` survive for all four integration kinds:
operational audit only (kind, type, status, timestamps), no mirrored third-party content, capped per
connection by the sync-job pruner. Global identity rows (`user`, `organization`,
`identity_provider`) are cross-tenant shared and never touched here.

### Reviewer lists are dated snapshots

A pull or merge request's review requests (the people asked, with GitLab's per-reviewer review state,
and GitHub's teams) arrive whole from both a webhook and a sync. A payload's `updated_at` can only rule
out an older payload, not order two within the same second: GitHub asks each reviewer in a
[`review_requested` event](https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request)
of its own, often within one second, and an approval's GitLab hook can carry the `updated_at` from before
the approval (below). So each list is stored with when Hephaestus received it, and a list received
earlier than the stored one changes nothing: a backlogged webhook or an old sync page cannot remove,
re-add or restate a request. A list received at the same instant as the stored one applies, so a
redelivery restates what it said. A writer reads the pull request with its row locked before it compares
(`PullRequestRepository.findForUpdateByRepositoryIdAndNumber`). A list that was not read whole is not
applied: GitLab's nested `reviewers` connection is completed by `GetMergeRequestReviewers`, and GitHub's
single `reviewRequests(first: 100)` page is left unused when GitHub counts more.

The two dates come from two clocks. A webhook's is the time JetStream stored it, on the NATS server's
clock, and a JetStream redelivery keeps it; a sync page's is the time Hephaestus asked for it, on the
application's clock. Comparing them assumes both hosts keep time by NTP, so their skew is far below
the seconds between a reviewer change and the next read. Two gaps remain. A provider that delivers a
webhook late, or retries a failed delivery, dates it by its arrival, not by when the provider sent
it. And a provider's redelivery that reaches the receiver after `hephaestus.webhook.stream.duplicate-window`
(10 minutes by default) is a new message with a new date. Either can restate an old list over a sync
page read in between, until the next sync reads the list again.

GitLab's webhooks state each reviewer's review from 18.6
([`reviewers_hook_attrs`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.6.0-ee/app/models/concerns/issuable.rb#L589-597)); before, they name
the reviewers only ([`issuable.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/lib/gitlab/data_builder/issuable.rb#L31)). A reviewer
named without a state keeps the state stored, so on GitLab before 18.6 who is asked follows the
webhooks and where each review stands follows the sync. A state Hephaestus does not know clears the
stored one, so a newer GitLab's states cannot leave an old verdict standing. From 18.6 a re-request runs a merge request
hook of its own
([`request_review_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.6.0-ee/app/services/merge_requests/request_review_service.rb#L10-28)),
and from 19.3 so does a submitted review
([`update_reviewer_state_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v19.3.0-ee/app/services/merge_requests/update_reviewer_state_service.rb#L67-75)).

On GitLab, every change to or from a verdict, every submitted review and every re-request writes a
system note on the merge request: approving, unapproving, requesting changes and submitting a review
([`system_notes/merge_requests_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/system_notes/merge_requests_service.rb#L172-194)),
and re-requesting a review
([`request_review_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/merge_requests/request_review_service.rb#L18)).
Starting a review writes none, which is harmless: a started review is still requested, as an
unreviewed one is. Saving a note touches its merge request
([`note.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/models/note.rb#L210), `after_save :touch_noteable`), so the merge
request's `updatedAt` moves and the incremental sync's `updatedAfter` watermark reads the new state even
when its webhook was missed. The approval's webhook itself can carry the old `updated_at`: GitLab writes
the approval note and runs the approval hooks in separate background jobs
([`event_store.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/lib/gitlab/event_store.rb#L52-54)), so the hook can be built before
the note touches the merge request. On GitHub a review request or its removal advances the pull
request's `updatedAt`, as the recorded `pull_request.review_requested` and `review_request_removed`
fixtures show, so the incremental sync reads a missed one.

### A GitLab merge request's review decision comes from its reviewers

GitLab's `approved` says only that the approval rules are met
([`approval_state.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/models/approval_state.rb)), and a
project that requires no approval meets them with nobody approving. So the sync
(`GitLabMergeRequestProcessor#reviewDecision`) derives the decision from what people did:

- `CHANGES_REQUESTED` while a request for changes stands: the `REQUESTED_CHANGES` merge status where the project
  blocks merging on one (Premium), or any reviewer's `REQUESTED_CHANGES` review state on every tier.
- `APPROVED` when someone approved and `approved` is true.
- `REVIEW_REQUIRED` otherwise, including an approval that leaves required approvals missing.
- None when the reviewer or approver list was not read whole: either could hide a decision.

`approvalsRequired` is not read, because the Community Edition schema has no such field. A request for changes is
attributed to a person only from the system note "requested changes" that names them
(`GitLabReviewReconciler#recordSystemNote`). The `detailed_merge_status` embedded in a note hook belongs to the merge
request and is repeated on every note while anyone's request stands. A live note of that kind also dismisses its
author's approval, because GitLab withdraws a reviewer's approval without a note or hook of its own
([`update_reviewer_state_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/app/services/merge_requests/update_reviewer_state_service.rb)).

A webhook names one person's act, not the whole decision:

- `approved`/`approval` and `unapproved`/`unapproval` differ only in whether the rules were met afterwards
  ([`execute_approval_hooks_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/ee/merge_requests/execute_approval_hooks_service.rb),
  [`remove_approval_service.rb`](https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/ee/merge_requests/remove_approval_service.rb)).
- A hook or live system note that is accepted (the gate below) and changes where someone's review stands leaves the
  stored decision unknown, with the mergeability and merge status GitLab derives from the approvals, until the readiness
  read after the hook (below) or the sync reads the merge request again; a read that fails leaves them unknown. A hook
  the gate rejects changes none of them. The note touches the merge request,
  so the incremental sync does read it.
- A redelivery changes no one's review, so it leaves a decision the sync has since read in place.
- An approval act, or GitLab's reset, applies only when both hold: the version the hook describes (`updated_at`) is not
  older than the stored one, and the head it names is the stored head. However late it arrives, a hook describing an
  older version or another head changes none of the stored review facts; an equal version applies. This also turns
  away a legitimate approval hook that GitLab built before its note advanced `updated_at` (above) once the newer version
  is stored: that approval is recorded by the readiness read after the newer event, or by the next sync, which read the
  approver list; until one succeeds, the decision it would change stays as that newer event left it. An act received
  before a stored read of the approvals is part of that read (the dated snapshot above). Two people's acts received at
  the same instant both apply.
- GitLab resetting approvals after a push sends `unapproved`/`unapproval` marked `system`, with `system_action`
  `approvals_reset_on_push` (all of them) or `code_owner_approvals_reset_on_push` (Code Owners' only)
  ([system-initiated events](https://docs.gitlab.com/user/project/integrations/webhook_events/#system-initiated-merge-request-events)).
  Its user is whoever pushed, and it does not say whose approvals went, so it leaves the decision unknown and dismisses
  no one; the readiness read reconciles the approvals with GitLab's whole approver list.
- A push leaves the decision, mergeability and merge status unknown for the new head; the recorded approvals stay, since
  a project can keep them across a push. A GitLab approval's stored commit is its recorded association, not proof of
  the originally approved commit.

### A GitLab merge request's readiness is read after its webhook

A merge request hook carries none of what merge advice depends on: merge status, head pipeline, approvals. After an
opened, updated, reopened or approval hook is stored, `GitLabMergeRequestMessageHandler` reads that one merge request
(`GetMergeRequestReadiness`, `GitLabMergeRequestReadinessReader`) outside the hook's transaction, and
`GitLabMergeRequestProcessor#applyReadiness` records it in a second short one, with the row locked, only where the
delivery may still write to the project as stored now (`GitLabWebhookContextResolver#mayStillWrite`): on a connection
route the connection is held active and the project admitted again — same GitLab instance, inside the group, still
monitored — so a project removed from monitoring or moved out while GitLab was read takes nothing from the read; off a
route it must still pass the scope filter for the same workspace. Mutable readiness is recorded only when GitLab names
this project and merge request, both open or both merged, at the stored head and not an older version; it never moves the head. Reviewers, decision and approvals — and the
mergeability and merge status GitLab derives from the approvals — follow the dated snapshot, dated by when the read was
asked for, as they do for a sync page: a read or page begun before a newer one was stored changes none of them. While GitLab reports `checking` or `approvals_syncing`, mergeability and
approvals are not settled and stay unknown. A read that fails records nothing; the next hook or sync reads again. No
workspace sync runs for a hook. A merge hook stores the merge commit it names but often no merger and no merge
time; the same read after it fills them in where GitLab names them (`applyTerminalFacts`), under the same
identity and head fence, and never replaces a recorded merger. These completed merge facts do not replace mutable
content and do not require GraphQL's second-precision version to equal the webhook's millisecond version. The merge occasion is offered only
after that read, in the same short transaction, and is offered even when the read failed. A merge the hook missed and a sync finds
is recorded as a sync-discovered occasion, which a later delivery of the hook can still claim.

For a merged merge request, the native approvals route can supply each standing row's creation time as `approved_at`
([GitLab approvals API](https://docs.gitlab.com/api/merge_request_approvals/)). It does not identify the originally
approved commit or provide an atomic approval history. A whole accepted approval snapshot matches the native row set
before recording those dates; a failed optional date read preserves an already-standing date, while an accepted absent
or conflicting date leaves it unknown. Renewal clears a withdrawn row's date.

When GraphQL's version is older, only dates of existing standing approvals can be reconciled: the native response must
name the same merged request and exact stored version, with matching non-null title and description, and its actor set
must match both GraphQL's whole current-head approvers and the canonical standing rows. Otherwise the read cannot change those dates. An accepted date-only read advances the existing approval-read clock, including when the date is unchanged;
it changes no membership, readiness or commit and emits no approval act. This also orders overlapping date-only reads.

GitLab answers a field it could not resolve with `null` and an error at that path. Every GitLab merge request read —
this one, the sync and the historical backfill — asks Spring for the errors at, above or below the exact field
(`ClientResponseField#getErrors`, with `nodes[i]` on a page), so a failed field is unknown, never a value: a failed
`approved` is not a refusal, a failed approver list is not an empty one, and a failed `headPipeline` — or its status or
SHA — records no check state. Only `headPipeline: null` with no error means GitLab reports no pipeline. A page that did
not capture a merge request's `updatedAt` changes nothing stored about it, and one that did not capture its
`diffHeadSha` records nothing that holds for a head — decision, mergeability, approvals, a missing pipeline — since the
stored head is kept and is not the one the page read; a pipeline the page names with its SHA is still recorded for it.

### Head checks are dated observations

`CheckState` tells apart `NO_PIPELINE` (GitLab reports no pipeline for the head, which says nothing about whether CI
is configured) and `SKIPPED` (a skipped pipeline) from `NONE`, GitHub's empty rollup and, on GitLab records stored
before, either of the two. A GitLab check observation is stored with when the provider was asked or sent it
(`head_check_observed_at`): the sync and the readiness read by when they asked, the pipeline hook by when JetStream
stored it. An observation older than the stored one changes nothing, whichever commit either is about, so a read begun
before a pipeline finished, or a delayed hook, cannot put back a state GitLab has since replaced; a later one replaces it
outright, including a pipeline that passes on a retry. A record of unknown age takes any dated observation. GitHub's
check paths are undated and unchanged.

## Documented asymmetries and residuals

- **Slack has no deletion *sweep* at all.** Its content model is append-plus-watermark and a message
  absent from a `conversations.history` page usually means pagination truncation, a filtered subtype,
  or a thread reply — inferring deletion from absence would mass-delete. Deletions arrive as events
  instead. Outline *does* infer deletion from absence, but not as a separate sweep phase: it
  tombstones inline at the end of each collection's clean enumeration, under the same
  reconciliation-only rule as the SCM sweeps.
- **Cross-owner GitHub transfer heals only on reconcile.** The event derives the *new* owner's
  subject and so reaches no filter of the old workspace. The next reconcile resolves the monitor by
  `native_id`. GitLab has the identical residual across root groups.
- **Legacy rows without `native_id`** fall back to a previous-`owner/name` lookup, which a
  simultaneous rename-and-transfer can miss; those rows acquire a `native_id` on their next sync.
- **No replay across a filter rebuild.** Repository-scoped events published between a rename and the
  consumer-filter rebuild were never pending for the durable consumer and are not redelivered; the
  next reconcile's full pass backfills them.
- **Slack paused gaps are never backfilled.** Resuming a PAUSED channel stamps the watermark to the
  resume instant, so messages sent while monitoring was off stay out of the mirror permanently.
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
