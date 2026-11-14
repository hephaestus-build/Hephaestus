# ADR 0094: A provider mirror changes only on a definitive provider answer, and a tombstone is never an erasure

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0012](0012-cross-instance-identity-safety-on-sync-paths.md), [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md)

**Builds on:** [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md), [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md), [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md), [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md)

**Relates to:** [ADR 0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md), [ADR 0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md)

## Context

Hephaestus mirrors work from GitHub, GitLab, Slack and Outline into PostgreSQL. Reviews and Heph read the mirror, not the provider.
Two questions decide the life of a mirror row. Is the work still there upstream? Does the workspace still have a basis to hold it?

ADR 0024 answered them with two operations, and six updates moved its answer. ADR 0012 added identity rules for sync and was narrowed once.
Some of that text no longer held. A GitHub 404 no longer removes a monitor, Slack now infers deletions, and person erasure runs in the app.

This ADR states the mirror lifecycle at 1.0 as rules. [Sync lifecycle](../contributor/sync-lifecycle.md) keeps the detail for each provider.
A rule that main does not satisfy yet says "1.0 target".

## Decision drivers

- A wrong deletion is invisible. A phantom row is visible and heals itself. Inference must lean toward doing nothing.
- A provider often cannot tell "gone" from "you may not see it". Only an answer that names a deletion is proof.
- A mirror row is shared by every workspace that monitors it ([ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md)).
- When the basis to hold personal data ends, the copy must go, including derived rows and copies in backups.

## Considered options

1. **One deletion mechanism for both questions:** tombstone everywhere, or hard delete everywhere. A tombstone keeps queryable personal data after the basis ends. A hard delete destroys a row that the next sync must restore.
2. **Two operations that share no code (chosen):** a reversible drift tombstone, and an irreversible mirror erasure.
3. **Per-provider handling.** This is the state before ADR 0024. GitLab could not erase its mirror at all.

## Decision

1. **Scope and ingest.** A workspace reaches a mirror row only through its monitored scope ([ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md)). Provider events reach the server through the PostgreSQL inbox ([ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md)).
2. **Sync job types.** An `INITIAL` job fills a new mirror and never sweeps. Only a `RECONCILIATION` job may infer a deletion from absence. A client can start only `RECONCILIATION` and `BACKFILL`.
   Enforced by: `SyncStatusServiceTest#triggerSync_initialType_throwsStateConflictAndRecordsNoJob`. No test asserts the `RECONCILIATION` gates in the two SCM schedulers and `OutlineDocumentSyncService`.
3. **Only a definitive provider answer removes or tombstones.** A definitive answer is a deletion event from the provider, or a complete listing that omits the row. A complete listing reached its last page with no error, rate limit, page cap or cancellation. Its count also equals the provider's total. Any doubt deletes nothing.
   Enforced by: `GitHubDeletionSweepServiceTest#shouldDeleteNothingWhenPaginationAbortsMidListing` and `#shouldDeleteNothingWhenUpstreamTotalCountDisagreesWithWhatWasReceived`, `GitLabDeletionSweepServiceTest`, `SlackHistoryReconcilerTest#shouldRemoveNothingWhenTheWindowsLastPageFails`.
4. **A missing repository is unavailable, not deleted.** A GitHub 404 or a lost installation marks the monitor unavailable. The sync retries once, then checks again each day. It never removes the monitor. A transient failure keeps the normal retry. A GitLab project that moves out of the connected group is a definitive answer, and its monitor goes.
   Enforced by: `GitHubDataSyncServiceTest#shouldPreserveSyncTargetWhenRenamedRepoHasStableIdOnNotFound` and `#shouldKeepNormalRetryWhenMetadataFetchIsRateLimited`, `RepositoryAvailabilityIntegrationTest#shouldRetryOnceThenWaitOneDayWhenRepositoryRemainsUnavailable`.
5. **A row keeps its identity across rename and transfer.** Sync keys a repository by `(native_id, provider_id)`, never by name. A rename re-keys the mirror row and every monitor of it. A transfer to another owner heals on the next reconcile.
   Enforced by: `GitHubDataSyncServiceTest#shouldTrackStableIdInsteadOfReusedNameWhenUnavailableOrHealthy` and `#shouldKeepRetainedWorkVisibleAfterAnotherWorkspaceResolvesARename`.
6. **Every login lookup names one provider instance.** The database holds `uq_organization_provider_login` and `uk_user_provider_login`. A GitHub lookup may name the type, because GitHub has one instance. A GitLab lookup names the provider id.
   1.0 target: delete `UserRepository.findByLogin`, which returns the first match across providers, and move its callers to the provider id. Move `GitLabIssueTypeSyncService` to the provider id. No pull request delivers it yet.
   Enforced by: `IntegrationSubjectBoundariesTest#noUnscopedFindByLogin`, for organizations only.
7. **A drift tombstone is reversible.** It sets `deleted_at`, and the next upsert of the row clears it. A Slack tombstone also erases the message text, so it cannot be undone. An archived Outline document is not deleted.
   Enforced by: `GitLabDeletionSweepSelfHealIntegrationTest#tombstonedIssueIsRevivedWhenTheUpstreamUpsertSeesItAgain`, `SlackHistoryReconcilerIntegrationTest#shouldEraseTheTextOfADeletedMessageAndKeepEverythingElse`, `OutlineDocumentSyncIntegrationTest#archivedDocument_isNotTombstonedByAbsence_realPostgresRoundTrip`.
8. **A read that speaks about live work refuses a tombstoned row.** That covers Heph's context, the project inventory and the sync counts. A record of what happened keeps the row: activity, scores, profiles and delivered feedback. No entity-level filter exists. Each query decides.
   Enforced by: `MirrorQueryPredicateGuardTest#everyMentorArtifactRangeExcludesTombstones`, `#bothProjectInventoryQueriesExcludeTombstones` and `#everySyncStatusSubEntityCountExcludesChildrenOfTombstonedParents`. The record side is not enforced. Review catches a new filter there.
9. **Removed work stops new review work.** A deletion removes the item. Its current results end as `REMOVED`, a pending occasion is withheld as `ITEM_GONE`, and new feedback stops with `ITEM_GONE`. A later event on restored work restores the item and can start a new review ([pipeline](../contributor/practice-review-pipeline.mdx)).
   A tombstoned Outline document stops with `ITEM_REMOVED`, as SCM work does, because a removal is never missing evidence.
   Enforced by: `ResultAdmissionIntegrationTest#shouldEndCurrentResultsAsRemovedWhenAnScmDeletionIsRecorded`, `ItemIndexServiceIntegrationTest#shouldKeepIdentityAcrossRemoveAndRestore`, `DocumentItemSourceIntegrationTest#shouldStopAsRemovedWorkWhenOutlineDeletedTheDocument`.
10. **Disconnect and workspace purge erase the mirror.** Both triggers reach one eraser for each integration and the same row set. The erase is a hard `DELETE`, tombstoned rows included. Derived rows go first. For SCM, `ScmMirrorErasedEvent` erases them. Disconnect fences sync, and a failed erase rolls the disconnect back. Provider teardown runs after the commit.
    1.0 target: an Outline disconnect also erases `docs.document` items, results and feedback. On main only purge removes them. No pull request delivers it yet.
    Enforced by: `ConnectionDisconnectErasureIntegrationTest#shouldKeepConnectionActiveWithCredentialsWhenLocalEraseFails` and `#shouldLeaveProviderUntouchedWhenDisconnectFailsToCommit`, `ScmWorkspaceContentEraserTest#erase_publishesTheDerivedRowEventBeforeDroppingTheItemsItPointsAt`.
11. **Erasure spares what another workspace holds.** A shared repository goes only when no monitor of any workspace points to it. The organization tier goes only when no other workspace is bound to the organization. Global identity rows stay.
    Enforced by: `ScmWorkspaceContentEraserTest#erase_delegatesTheCascadeToTheOrphanGuardedDeleteForEveryMonitoredRepository` and `#erase_dropsOrgTierMirrorOnlyWhenThisIsTheLastWorkspaceForTheOrganization`.
12. **Operational audit stays.** `sync_job`, `connection_activity` and `connection_audit` survive both triggers. They hold no mirrored content.
    Not enforced. Review catches a content column on these tables.
13. **Disconnect is storage limitation, not a person's erasure.** The UI and the docs never call a disconnect "GDPR erasure". Erasing the mirror never changes the source on the provider.
    Not enforced. Review of UI text catches it.
14. **Person erasure runs in the app.** `PersonDataService` erases one person across every workspace and every registered store. Account deletion reaches it through `AccountPurger` ([ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md)). An instance admin starts it for anyone else.
    Enforced by: `AccountErasureIntegrationTest#erasesTargetAcrossTwoWorkspacesAndEveryRegisteredStoreWithoutTouchingAnotherPerson`.
15. **A restore reapplies person erasures before work resumes.** Off-host pgBackRest backups are optional and off by default. Erased data stays in a backup until that backup expires. After a restore, the `erasure-recovery` profile reapplies each later person erasure from a private manifest. It does this before schedulers and consumers start.
    Enforced by: `PersonDataRecoveryIntegrationTest#shouldReapplyAnErasureLostByARestoreBeforeProcessingStarts`, `PersonDataRecoveryStartupIntegrationTest#shouldBindTheRecoveryProfileAndCompleteBeforeSchedulersAndConsumersStart`.
16. **An erasure survives a backup restore.** The `erasure-recovery` profile reapplies every erasure after the recovery point. It reapplies disconnects, workspace purges and person erasures, also of accounts that their owners deleted.
    The operator lists them in a version 2 manifest outside the database. The profile runs with background work off and refuses to start beside it, so the purge needs no quiescence guard.
    [Reapply erasures after a restore](../admin/erasure-recovery.md) owns the procedure.
    Enforced by: `ErasureRecoveryRestoreIntegrationTest#shouldPurgeAgainAWorkspaceThatARestoreBroughtBack`, `ErasureRecoveryRestoreIntegrationTest#shouldDisconnectAgainAConnectionThatARestoreBroughtBack`, `PersonDataRecoveryIntegrationTest#shouldRefuseToRecoverBesideBackgroundWork`.

## What changed, and why

- **A GitHub 404 no longer removes a monitor** (ADR 0012 and its update). GitHub answers 404 for a private resource that a request cannot see, so a 404 does not prove a deletion. Rule 4 marks the monitor unavailable instead.
- **The unique login constraint shipped.** ADR 0012 rejected it as a follow-up. It is now part of rule 6.
- **Slack infers deletions** (ADR 0024 update of 2026-11-03). It compares only rows that it stores with a complete read of their range. Rule 3 covers it.
- **The signal vocabulary of the updates is gone.** `ARTIFACT_NOT_VISIBLE`, the resubmitters and `PracticeFeedbackDeliveryPolicy` went with the signal ledger. Rule 9 states the item model.
- **Person erasure is not an operator process** (ADR 0024 § (c) and (d)). It runs in the app (rule 14).
- **Backups exist** (ADR 0024 § Consequences). The revisit trigger fired. Rules 15 and 16 state the erasure cycle.
- **The NATS subject design of § (e) goes** with [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md). The identity rule stays as rule 5.

## Consequences

- Good: each integration answers "gone upstream" and "basis ended" the same way. A new provider chooses whether it sweeps, not a deletion model.
- Good: a broken credential or a private repository never loses a workspace's monitors.
- Good: disconnect and purge reach one end state, which a test can count.
- Bad: a repository that is really deleted but answers 404 stays as an unavailable monitor until an admin removes it.
- Bad: a tombstoned row stays queryable personal data until a sweep confirms it again or an erasure removes it.
- Bad: a backup keeps erased data until it expires. Retention is a count of backups, not a time.
- Bad: until rule 16 is decided, a restore can bring back a disconnected workspace's mirror.

## Confirmation

Each rule names its test or says that it is not enforced.
Rules 12, 13 and 16 rely on review. Rule 6 is enforced for organizations only.

## Revisit trigger

- A provider ships a reliable deletion event with redelivery. Then that provider can stop sweeping.
- `sync_job` or `connection_activity` gains a column with mirrored content.
- A mirror table gains a `workspace_id`. Then scoped deletes replace the orphan guard.
- Hephaestus supports a second GitHub instance. Then GitHub lookups name the provider id too.

## Sources

- GitHub Docs, [Troubleshooting the REST API](https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api): GitHub can answer 404 for a private repository that exists. It does this "to avoid confirming the existence of private repositories". This is why rule 4 treats a 404 as unavailable, not deleted.
- GDPR, [Article 5(1)(e)](https://eur-lex.europa.eu/eli/reg/2016/679/art_5/oj): personal data are kept "for no longer than is necessary for the purposes". This is the basis of rule 10. [Article 17(1)](https://eur-lex.europa.eu/eli/reg/2016/679/art_17/oj) gives "the data subject" the right to erasure "without undue delay". That is a person's request, so it is rule 14 and not a disconnect (rule 13).

## Already priced and declined

- [ADR 0012](0012-cross-instance-identity-safety-on-sync-paths.md#rejected-alternatives): the provider on a ThreadLocal at the sync entry point.
- [ADR 0012](0012-cross-instance-identity-safety-on-sync-paths.md#rejected-alternatives): removal of the monitor on any empty result, with a retry in a higher layer.
- [ADR 0012](0012-cross-instance-identity-safety-on-sync-paths.md#update--2026-09-17): removal of a monitor with no `native_id` on a GitHub 404.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#considered-options): one deletion mechanism for both questions.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#considered-options): per-provider deletion handling.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#b-mirror-erasure--irreversible-triggered-by-a-lawful-basis-change): an inbound port for derived-row erasure, which closes a module cycle.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#update--2026-09-03-issue-1404-which-reads-honour-a-drift-tombstone): an entity-level `@SQLRestriction` on tombstoned rows.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#update--2026-09-05-issue-1825-live-occasions-share-the-reversible-hold): a global ORM filter, and a shared practice gate.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#update--2026-09-05-issue-1825-live-occasions-share-the-reversible-hold): admission serialized with deletion, a synchronous upstream query, and the cancel of reviews in flight.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#consequences): Slack never infers a deletion from absence.
- [ADR 0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md#update--2026-11-03-slack-infers-a-deletion-from-a-complete-read): a stateless rotation of the Slack history check.
