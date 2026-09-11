# ADR 0024: Integration sync lifecycle — drift tombstones and mirror erasure are two different operations

**Status:** Accepted
**Date:** 2026-07-18
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0015](0015-unified-integration-framework.md) (integration framework), [ADR 0008](0008-webhook-runtime-role.md) (webhook runtime role, non-redeliverable deliveries), [ADR 0023](0023-outline-documentation-integration.md) (Outline as a content source)

## Context

Hephaestus mirrors third-party content — GitHub and GitLab issues/pull requests/merge requests and
their reviews and comments, Slack messages, Outline documents — into PostgreSQL, because practice
reviews and mentor context read the mirror, not the vendor. Two questions about that mirror had
never been answered the same way twice across the four integrations.

**"Upstream no longer has this row."** Every sync path is upsert-only, so a deleted artifact
survives locally forever. Webhooks cannot close the gap: this deployment's inbound deliveries are
not redeliverable (ADR 0008), GitHub has no `pull_request.deleted` action at all, and GitLab emits
no issue- or merge-request-deletion event whatsoever. A phantom row inflates the counts the admin
UI reports and keeps feeding reviews as if it were live.

**"This workspace's basis for holding the mirror is gone."** Slack and Outline hard-deleted their
mirrored rows on both admin disconnect and workspace purge. GitHub erased only on the vendor-side
`installation.deleted` webhook — an admin disconnect erased nothing. GitLab, which has no
vendor-side uninstall signal at all because it authenticates with a user PAT, erased nothing on any
trigger: its mirror was un-erasable except by hand. Workspace purge cleared monitors and local git
clones and left every mirrored row in Postgres. `docs/admin/dsms/record-of-processing.md` meanwhile
promised that repository artefacts are removed "when the last workspace stops monitoring the source
repository" — true only for the App-uninstall webhook and single-repo monitor removal.

The two questions look similar and are not. Answering them with one mechanism is the failure mode
this ADR exists to prevent.

## Decision drivers

- A wrong deletion is invisible; a phantom row is visible and self-correcting. Any inference-driven
  removal must therefore be biased hard toward doing nothing.
- SCM tables carry no `workspace_id`. `repository` and its cascade are instance-global and genuinely
  shared between workspaces that monitor the same source repository
  (`BaseGitServiceEntity` keys on `(provider_id, native_id)`).
- Regulators treat a soft-deleted row that remains queryable as retained personal data. A retention
  trigger that only sets a flag has not erased anything.
- Adding a vendor should stay flat in cost (ADR 0015): one shape per concern, not one per vendor.

## Considered options

1. **One deletion mechanism for both questions** — tombstone everywhere, or hard-delete everywhere.
2. **Two mechanisms, deliberately sharing no code**, each wired to its own triggers.
3. **Status quo** — per-vendor ad-hoc handling.

## Decision

Option 2. The mirror has exactly two deletion semantics, and neither may be implemented in terms of
the other.

### (a) Drift tombstone — recoverable, inferred from absence

A `RECONCILIATION` pass — and only a `RECONCILIATION` pass — may set a `deleted_at` marker on rows the
upstream listing no longer contains: `GitHubDeletionSweepService`, `GitLabDeletionSweepService`, and
`OutlineDocumentSyncService#tombstoneVanished`. Slack does not sweep (see Consequences).

Deletion is authorized by **provable completeness and nothing else**. `UpstreamListing.complete()`
starts `false` and is set only on the one clean exit: pagination ran to `hasNextPage == false`, no
rate-limit abort, no GraphQL error, no exception, no page-cap truncation, no cancellation, and a
node count that agrees exactly with the server's own `totalCount`. Any doubt — including a doubt
the code did not anticipate — skips the entity class and deletes nothing. A partial listing is
never merged with a previous one. Outline's equivalent: a truncated enumeration throws rather than
returning a short list, and a budget-exhausted pass tombstones nothing.

Tombstoning rather than deleting is what makes acting on inference tolerable: `upsertCore` clears
`deleted_at`, so a wrongly-swept repository heals itself on the next ordinary sync.
`INITIAL` jobs deliberately do not sweep — a mirror still being populated has nothing stale in it,
and every row not yet fetched would look like an upstream deletion to a set difference. For the
same reason a manual `INITIAL` is rejected at the API (`SyncStatusService`); only `RECONCILIATION`
and `BACKFILL` are client-triggerable.

This rule is **uniform across every integration that infers deletion from absence** — it is not an
SCM-only convention. Each `IntegrationSyncRunner` forwards its `SyncJobType` instead of dropping it,
and each inference sits behind a `type == RECONCILIATION` gate: the two SCM schedulers before their
sweep phase, and `OutlineDocumentSyncService#syncOneCollection` before `tombstoneVanished`. The two
authorizations are independent and both are required — provable completeness **and**
`RECONCILIATION` — so a budget-exhausted Outline enumeration still deletes nothing on a reconcile.
Slack needs no gate because it never infers deletion from absence at all.

### (b) Mirror erasure — irreversible, triggered by a lawful-basis change

Two triggers, one choke point per integration, identical row set from either:

- Admin disconnect (`PATCH /workspaces/{slug}/connections/{id}/status` → `UNINSTALLED`), whose
  `revoke` callback runs inside the fenced `ConnectionService#disconnect` transaction — stale sync
  leases reaped, running jobs cancelled or refused with a retryable 409, so sync is provably
  stopped before erasure runs.
- Workspace purge (`WorkspaceStatus.PURGED`), via a `WorkspacePurgeContributor` at order `-200`.
  `PURGED` is a soft delete, so `ON DELETE CASCADE` on `workspace_id` never fires and every module
  must delete its own rows.

The choke points are `SlackWorkspaceContentEraser`, the Outline strategy's document/collection
deletes, and — new — `ScmWorkspaceContentEraser` for GitHub and GitLab. All are hard `DELETE`s,
including of any rows a drift sweep had tombstoned.

**Erasure is orphan-guarded because SCM rows are shared.** `ScmWorkspaceContentEraser` removes
*this* workspace's `repository_to_monitor` rows, flushes, and delegates each repository to
`WorkspaceRepositoryMonitorService#deleteRepositoryIfOrphaned`, which drops the shared row only when
no monitor anywhere still points at it. A repository another tenant still monitors survives — the
surviving tenant's basis persists, while the erased tenant's access path (monitor row, NATS consumer
filter, and every workspace-scoped query) is gone. The org tier (`team`, `team_membership`,
`organization_membership`) has its own equivalent guard, since no repository cascade reaches it: it
is erased only when no other non-purged workspace is bound to the same `Organization`. The
`Organization` row itself is global and is unlinked, never deleted. There is no global delete
anywhere in the eraser.

Derived rows go first, while the artifacts they reference still exist: the eraser publishes a
synchronous in-transaction `ScmMirrorErasedEvent`, and `practices` and `activity` listen. They are
not reachable from the repository cascade — `observation.artifact_id` and `feedback.artifact_id` are
soft references with no FK, and the `evidence` jsonb quotes mirrored diff and comment text verbatim.
An event rather than an inbound port because both modules already depend on `workspace`, so a port
would close a Spring Modulith cycle.

### (c) Retained on purpose

`sync_job`, `connection_activity` and `connection_audit` survive both erase triggers for all four
integration kinds. They are operational audit — kind, type, status, timestamps, no mirrored
third-party content — capped per connection by the sync-job pruner, and retaining them uniformly is
what makes a disconnect auditable after the fact. Global identity rows (`user`, `organization`,
`identity_provider`) are never touched: they are cross-tenant shared, and person-level erasure is a
separate operator process.

### (d) Legal framing

Disconnect/purge erasure implements **Art. 5(1)(e) storage limitation**, not Art. 17. Art. 17 is a
*data subject's* right exercised against the controller; disconnect is a *customer admin's* action.
Once the integration — the sole purpose for holding the mirror — is severed, retention has no basis.
An actual Art. 17 request against a person's data across workspaces, including `user` and account
rows, remains the separate operator-executed process the DSMS record describes. The disconnect
control must not be labelled "GDPR erasure" in the UI or in operator docs. Erasing the mirror never
touches the source: content on GitHub, gitlab.lrz.de, Slack or Outline is not modified.

This is also why (a) can never implement (b): a tombstoned row is still queryable retained personal
data. And why (b) can never implement (a): a hard delete destroys data the drift sweep expects to be
able to resurrect on the next upsert.

### (e) Identity healing across rename and transfer

A repository whose name changes upstream must not sever sync. GitHub `repository` lifecycle events
are therefore derived **org-scoped** as `github.<owner>.?.repository` and the handler is registered
on the `organization.` tier — the repo-name token is unstable across rename/transfer, so a
repo-tier subject built from the payload's new name matches neither the monitored-repo filter nor
the org filter and would be silently ACK-dropped, freezing that repository's data. This mirrors
GitLab, which already org-scopes its `project` events. The handler re-keys the mirrored row by the
stable `(native_id, provider_id)` and then re-keys **every** monitor tracking that repository —
repositories are shared, so one event can affect several workspaces — never by name alone.

## Consequences

**Positive.** The four integrations answer the same two questions the same way; adding a vendor
means choosing whether it sweeps, not inventing a deletion model. A GitLab-connected workspace can
erase its mirror at all, for the first time. Purge and disconnect provably reach the same end state,
which is directly testable as equal table counts. The DSMS record can state removal semantics that
the code actually implements.

**Negative / accepted.**

- **Erasure is irreversible and reconnect is a fresh initial sync.** Disconnecting an SCM connection
  destroys mirrored content, derived observations and feedback, and local clones for repositories
  this workspace was the last to monitor. Reconnecting re-fetches from the vendor and cannot restore
  what the vendor no longer has. This is a behaviour change for operators upgrading past this
  release — see `docs/contributor/migration-unified-integration.md`.
- **Cross-owner GitHub transfer does not heal in real time.** A transfer to a *different* owner
  derives the new owner's subject and so reaches no filter of the old workspace. It heals on the
  next reconcile pass, which resolves the monitor by `native_id`. GitLab has the identical residual
  for a transfer across root groups. Repository-scoped events published between the rename and the
  consumer-filter rebuild were never pending for the durable consumer and are not replayed; the next
  reconcile's full pass backfills them.
- **Legacy rows without `native_id`** fall back to a previous-`owner/name` lookup, which a
  simultaneous rename-and-transfer can miss. Those rows acquire a `native_id` on their next sync.
- **Slack does not sweep, by design.** A message absent from a `conversations.history` page usually
  means pagination truncation, a filtered subtype, or a thread reply — inferring deletion from
  absence would mass-delete. Slack-side deletions arrive as `message_deleted` (tombstone) and
  `channel_deleted` (erase our copy) events in every consent state.
- **Operational audit outlives the content it describes.** A disconnected workspace's `sync_job` and
  `connection_activity` history remains queryable. Accepted per (c); revisit if any of those tables
  starts carrying vendor content.
- **Backups.** No off-host backup of the Postgres volume is configured, so no "put beyond use"
  machinery is needed today — only the documented statement. Configuring one makes documenting the
  backup erasure cycle a prerequisite, not a follow-up.

## Revisit trigger

Any of: a vendor ships a reliable deletion webhook plus redelivery, making absence-inference
unnecessary for that integration; an off-host backup is configured, which makes the erasure guarantee
incomplete until a documented backup cycle exists; `sync_job` or `connection_activity` gains a column
carrying mirrored third-party content, which would move it from "retained audit" to "must be erased";
or SCM entities gain a `workspace_id`, which would retire the orphan guard in favour of scoped
deletes.

## Update — 2026-09-03 (issue #1404): which reads honour a drift tombstone

Supersedes nothing above; it answers a question section (a) left open. (a) says a tombstone makes a
row identifiable and reversible. It does not say who is allowed to keep reading it, and the answer
had been decided query by query, which is how the same developer could be shown a merged-pull-request
count and a list under it that disagreed.

There is no entity-level `@SQLRestriction` and there will not be one: reconciliation, resurrection,
retention and audit all read `deleted_at`, and a restriction that hid the column's own rows would
break the self-healing (a) rests on. Filtering is therefore per query, the convention
`SlackMessageRepository` and `OutlineDocumentRepository` already follow, and this section is the one
home for who does it.

**A tombstoned row is refused where Hephaestus would otherwise speak about work as if it were live.**

| Surface | Honours the tombstone | Why |
| --- | --- | --- |
| Mentor context (`MentorContextQueryRepository`) | Yes | Heph describes the developer's current work; citing something they cannot open is a wrong claim about their work. |
| Project-inventory evidence source (`WorkspaceInventoryContentSource`) | Yes | The "what else exists in this project" index staged into a review's evidence; listing work that is gone invites an observation about nothing. |
| A review somebody asks for (`ManualReviewRequests`) | Yes | A review of deleted work is wrong by construction. Refused as `SignalStateReason.ARTIFACT_GONE` rather than 404'd by the ownership check: the workspace does monitor that artifact, so the asker gets the reason instead of an error status. |
| A backfill or sweep campaign's scope (`ReviewBackfillScopeRepository`) | Yes | Predates this update — a campaign spends budget per artifact, so a tombstoned row never enters the scope rather than being refused per ask. |
| Sync-admin counts and the sweep's own listings | Yes | Predates this update — the number is the operator's answer to "what is in the mirror". |

**A tombstoned row stays visible where the record is of something that happened.**

| Surface | Honours the tombstone | Why |
| --- | --- | --- |
| Activity events, XP, leaderboard (`activity`, `leaderboard`) | No | An event is a historical fact: the review happened, and the reviewer earned it. Deleting the work upstream does not un-review it, and rewriting scores retroactively is a worse lie than an orphaned row. |
| Profile counts, contributed repositories, earliest-contribution dates (`profile`, `workspace`) | No | Same record, same reason; a profile that shrinks when somebody else deletes a branch is not a profile of the developer. |
| Observations and delivered feedback (`practices`) | No | Feedback that was delivered was delivered — the ledger says so, and hiding it at read time would make the ledger and the read disagree with no recorded reason. Suppressing feedback about a vanished artifact is a *delivery* decision and belongs to `FeedbackSuppressionReason.ARTIFACT_GONE`, decided by `PracticeFeedbackDeliveryPolicy` in the `agent` module, which may depend on `integration.scm`. `practices` may depend on the integration contract and on nothing that implements it, so it never names the `issue` table. |
| The gate loaders the pending-signal resubmitters read | No | They must tell "deleted upstream" apart from "temporarily swept": a resubmitter that cannot find the row records `SignalStateReason.ARTIFACT_GONE`, which is terminal, and the resurrection (a) promises would leave the occasion retired for good. So a review a person asks for on one artifact is refused with `SignalStateReason.ARTIFACT_GONE`; an automatically re-offered signal is not, and can still start a review of a tombstoned row. Retiring it needs a retryable reason meaning "temporarily swept", which the vocabulary does not have yet. |

**Slack and Outline are out of scope of this update, deliberately.** `SlackMessage` and
`OutlineDocument` carry their own `deletedAt` and their own repositories already filter it, so the
mirror reads are consistent. What is undecided is whether an observation grounded in a deleted
conversation thread or document should still be shown — the same question as the `practices` row
above, and it gets the same answer for the same reason, through the delivery policy rather than a
read filter.

**Consequence.** The vocabulary for "the work is gone" stays split by stage and stays typed:
`SignalStateReason.ARTIFACT_GONE` at the occasion stage, `FeedbackSuppressionReason.ARTIFACT_GONE` at
delivery. A capture-stage refusal written as a free-text job failure is a bug, not a third option —
`docs/contributor/practice-review-pipeline.mdx` § Where a review can stop.

**Revisit trigger for this section.** A read surface changes side; an artifact kind other than
`scm.pull_request` / `scm.issue` needs the same distinction; or a retryable signal-state reason for
"temporarily swept" exists, at which point a re-offered pending signal can be held rather than
allowed to review a tombstoned row.

## Update — 2026-09-04 (issue #1806): a re-offered signal is held, not retired

Supersedes the "gate loaders the pending-signal resubmitters read" row, the **Consequence**
paragraph and the **Revisit trigger for this section** of the 2026-09-03 update above; those stay
standing as the record of what was priced and declined. The trigger they named has fired:
`SignalStateReason.ARTIFACT_NOT_VISIBLE` now exists and resolves to `SignalState.PENDING`.

The gate loaders still hand back tombstoned rows and still will — that is what lets a caller tell a
missing row from a tombstoned one. What changed is the two SCM resubmitters, and only them:
`PullRequestSignalResubmitter` and `IssueSignalResubmitter` read `deletedAt` and record retryable
`ARTIFACT_NOT_VISIBLE`, so the occasion is held and re-offered until an ordinary sync resurrects the
row or its normal pending deadline lapses it. A missing or otherwise unreviewable row still records
terminal `ARTIFACT_GONE`, and a review somebody asks for is still refused with `ARTIFACT_GONE`.

**Scope, and what is still open.** The live-event submission paths — `AgentJobEventListener` and
`IssueAgentJobEventListener` — read the same gate loaders and do not make this distinction, so an
event arriving between a reconciliation sweep and the next ordinary sync can still start a review of
a tombstoned row. `ConversationReviewSubmitter` keeps terminal `ARTIFACT_GONE`, correctly: a Slack
tombstone wipes the message text under (b) and is genuinely irreversible. Outline has no
`PendingSignalResubmitter`, so its signals lapse on the deadline; its tombstone *is* reversible, so
adding one inherits this question.

**Revisit trigger.** The live-event submission paths need the same distinction; an artifact kind
other than `scm.pull_request` / `scm.issue` needs it; or Outline gains a resubmitter.

## Update — 2026-09-05 (issue #1825): live occasions share the reversible hold

Supersedes the live-event gap and that part of the revisit trigger in the 2026-09-04 update.
Live GitHub and GitLab occasions are held rather than dropped while their work is tombstoned.
The listeners record the occasion before admission, so the signal ledger provides both the recorded
outcome and the retry mechanism.

The automatic submission boundary owns the check: a global ORM filter would hide reversible absence,
while a shared practice gate would have to distinguish an automatic hold from an explicit request's
terminal refusal. The [glossary](../contributor/practice-review-glossary.mdx#signal-state-reasons) owns
that distinction; the [pipeline](../contributor/practice-review-pipeline.mdx#occasion) owns eligibility
and retry behavior.

This does not serialize admission with later deletion, query upstream synchronously, or cancel
in-flight reviews. Slack's irreversible deletion and Outline's lack of a resubmitter remain unchanged.

## Update — 2026-09-05 (issue #1404): capture, new feedback and the two non-SCM kinds

Supersedes the **Slack and Outline are out of scope of this update** paragraph of the 2026-09-03
update above; that paragraph stays standing as the record of what was open and why. Both of its tables
still hold, and so does the 2026-09-04 hold on a re-offered signal.

**Capture.** Each SCM artifact content source checks its own parent before it reads anything, whatever
kinds the caller selected — the capture-stage refusal the 2026-09-03 **Consequence** paragraph named as
the alternative to a free-text job failure. A missing or tombstoned parent reports
`SourceAbsenceReason.NOT_FOUND` with no files, no completeness claim and no content state, so an
unavailable source cannot be read as an empty one. Outline document capture already worked this way.
Clearing the tombstone permits a later capture; it does not rewrite evidence already recorded.
[The pipeline](../contributor/practice-review-pipeline.mdx#where-a-review-can-stop) owns each stage's
refusal vocabulary and the
[artifact-source contract](../contributor/artifact-source-contract.mdx#capture-state) owns capture
states.

**New feedback.** `PracticeFeedbackDeliveryPolicy` checks the current target on the artifact, in-app and
conversation surfaces alike, including feedback prepared for a conversation before the developer asks
for it, and refuses an unavailable target with `FeedbackSuppressionReason.ARTIFACT_GONE` in the
[delivery policy check order](../contributor/practice-review-glossary.mdx#the-delivery-policy-check-order).
Feedback already delivered stays readable, per the `practices` row of the 2026-09-03 table.

**The two non-SCM kinds** the superseded paragraph left open are answered the same way, through the same
mechanism:

| Kind | What decides a new delivery | Limit |
| --- | --- | --- |
| `scm.issue`, `scm.pull_request` | The row's tombstone, and that the recipient is the subject of the work | — |
| `docs.document` | The document still exists and is not tombstoned | Archiving a document, or evicting its body, is not deletion |
| `chat.conversation_thread` | The thread row, its channel's consent and the recipient's participation, through `ConversationSourceLiveness` | `SlackThread` has no tombstone column, so this is thread eligibility, not proof that every captured message still exists |

Integration-owned projections supply these facts, so `practices` still names no integration table.

**What these checks are not.** They observe the mirror at the moment they run. They neither lock sync
across Git I/O and external delivery nor take an atomic snapshot with the provider, so a successful
capture does not by itself authorize a later delivery.

**Revisit trigger.** A content source captures an artifact kind without checking its parent; Slack gains
a message-level tombstone; or delivery needs a reason other than `ARTIFACT_GONE` to tell "the work is
gone" apart from "this channel cannot carry it".
