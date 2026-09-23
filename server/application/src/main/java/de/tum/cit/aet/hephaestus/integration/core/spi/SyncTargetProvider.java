package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Main SPI for the integration.scm sync engine to discover and manage synchronization
 * targets. Extends {@link SyncTimestampProvider} (sync timestamps) and
 * {@link BackfillStateProvider} (backfill tracking).
 * <p>
 * The SPI is domain-agnostic: sync targets are identified by {@code syncTargetId},
 * scopes group sync targets and are identified by {@code scopeId}; the host application
 * maps its domain concepts onto these abstractions.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe. The sync engine may call
 * methods concurrently from multiple sync threads.
 */
public interface SyncTargetProvider extends SyncTimestampProvider, BackfillStateProvider {
    /**
     * Gets all active sync targets across all scopes — targets in active-status scopes with
     * at least one configured repository. Never null (may be empty).
     */
    List<SyncTarget> getActiveSyncTargets();

    /**
     * @return list of sync targets for the scope, never null (may be empty if scope not found)
     */
    List<SyncTarget> getSyncTargetsForScope(Long scopeId);

    /**
     * Checks if a scope is active and eligible for synchronization (e.g., not SUSPENDED or
     * PURGED). Check before initiating sync to avoid wasted API calls for disabled scopes.
     */
    boolean isScopeActiveForSync(Long scopeId);

    /**
     * Gets repository nameWithOwner values for a scope's active sync targets — a lightweight
     * alternative to full Repository entities when only the identifier is needed.
     */
    default List<String> getRepositoryNamesForScope(Long scopeId) {
        return getSyncTargetsForScope(scopeId).stream()
                .map(SyncTarget::repositoryNameWithOwner)
                .toList();
    }

    void updateSyncTimestamp(Long syncTargetId, SyncType syncType, Instant syncedAt);

    /** Record a failed resource pass, or clear only that pass's error after success. */
    void updateSyncError(Long syncTargetId, SyncPass pass, @Nullable String error);

    /**
     * Gets sync sessions for batch synchronization, scoped to a single provider kind.
     * Each session contains all sync targets for a scope with its sync context.
     * <p>
     * The {@code kind} argument keeps this SPI vendor-neutral: the caller (a GitHub or GitLab
     * scheduler/backfill service) passes the provider it already drives, instead of the SPI
     * exposing a vendor-named method per provider. Implementations filter active scopes by the
     * scope's configured provider kind.
     */
    default List<SyncSession> getSyncSessions(IntegrationKind kind) {
        return List.of();
    }

    default SyncStatistics getSyncStatistics() {
        return new SyncStatistics(0, 0, 0, 0, false);
    }

    /**
     * A batch of sync targets for a single scope, used for parallel processing.
     *
     * @param scopeId        unique scope identifier
     * @param slug           URL-safe scope identifier
     * @param displayName    human-readable scope name
     * @param accountLogin   GitHub organization/user login
     * @param installationId GitHub App installation ID (null for PAT auth)
     * @param serverUrl      per-workspace provider base URL (e.g. {@code https://gitlab.lrz.de}).
     *                       GitLab sync paths use it to look up the matching git_provider row
     *                       instead of stamping the global default, so identities on distinct
     *                       self-hosted instances never fuse. Null for GitHub workspaces
     *                       (always github.com).
     * @param syncTargets    repositories to sync in this scope
     * @param syncContext    thread-local context for logging and scope isolation
     */
    record SyncSession(
            Long scopeId,
            String slug,
            String displayName,
            String accountLogin,
            @org.jspecify.annotations.Nullable Long installationId,
            @org.jspecify.annotations.Nullable String serverUrl,
            List<SyncTarget> syncTargets,
            SyncContextProvider.SyncContext syncContext) {}

    /**
     * Statistics about sync target filtering for observability.
     *
     * @param totalScopes      total number of scopes in the system
     * @param skippedByStatus  scopes skipped due to non-active status
     * @param skippedByFilter  scopes skipped due to allowlist filtering
     * @param activeAndAllowed scopes that passed all filters
     * @param filterActive     whether allowlist filtering is enabled
     */
    record SyncStatistics(
            int totalScopes, int skippedByStatus, int skippedByFilter, int activeAndAllowed, boolean filterActive) {}

    /**
     * Scope-level sync metadata for organization-wide features.
     *
     * @param scopeId                  unique scope identifier
     * @param displayName              human-readable scope name
     * @param organizationLogin        GitHub organization login
     * @param organizationId           GitHub organization database ID
     * @param issueTypesSyncedAt       last sync of issue types (org-level feature)
     * @param issueDependenciesSyncedAt last sync of issue blocking relationships
     * @param subIssuesSyncedAt        last sync of sub-issues hierarchy
     */
    record SyncMetadata(
            Long scopeId,
            String displayName,
            String organizationLogin,
            @org.jspecify.annotations.Nullable Long organizationId,
            @org.jspecify.annotations.Nullable Instant issueTypesSyncedAt,
            @org.jspecify.annotations.Nullable Instant issueDependenciesSyncedAt,
            @org.jspecify.annotations.Nullable Instant subIssuesSyncedAt) {
        private static final long SECONDS_PER_MINUTE = 60L;

        public boolean needsIssueTypesSync(int cooldownMinutes) {
            return needsSync(issueTypesSyncedAt, cooldownMinutes);
        }

        public boolean needsIssueDependenciesSync(int cooldownMinutes) {
            return needsSync(issueDependenciesSyncedAt, cooldownMinutes);
        }

        public boolean needsSubIssuesSync(int cooldownMinutes) {
            return needsSync(subIssuesSyncedAt, cooldownMinutes);
        }

        private static boolean needsSync(@org.jspecify.annotations.Nullable Instant lastSyncedAt, int cooldownMinutes) {
            return (lastSyncedAt == null
                    || lastSyncedAt.isBefore(Instant.now().minusSeconds(cooldownMinutes * SECONDS_PER_MINUTE)));
        }
    }

    /**
     * A repository configured for synchronization within a scope.
     *
     * @param id                                  unique sync target identifier
     * @param scopeId                             parent scope identifier
     * @param installationId                      GitHub App installation ID (null for PAT auth)
     * @param personalAccessToken                 PAT for authentication (null for App auth)
     * @param authMode                            authentication mechanism to use
     * @param repositoryNameWithOwner             repository identifier in "owner/repo" format
     * @param lastLabelsSyncedAt                  last labels sync timestamp
     * @param lastMilestonesSyncedAt              last milestones sync timestamp
     * @param lastIssuesSyncedAt                  last issues sync timestamp
     * @param lastPullRequestsSyncedAt            last pull requests sync timestamp
     * @param lastDiscussionsSyncedAt             last discussions sync timestamp
     * @param lastCollaboratorsSyncedAt           last collaborators sync timestamp
     * @param lastFullSyncAt                      last full repository sync timestamp
     * @param issueBackfillHighWaterMark          highest issue number at issue backfill start
     * @param issueBackfillCheckpoint             current issue backfill position (counts down to 0)
     * @param pullRequestBackfillHighWaterMark    highest PR number at PR backfill start
     * @param pullRequestBackfillCheckpoint       current PR backfill position (counts down to 0)
     * @param backfillLastRunAt                   when backfill last executed
     * @param issueSyncCursor                     pagination cursor for resuming issue sync
     * @param pullRequestSyncCursor               pagination cursor for resuming PR sync
     * @param discussionSyncCursor                pagination cursor for resuming discussion sync
     * @param nativeId                            provider's stable numeric repository id (GitHub
     *                                            {@code repository.id} / GitLab {@code project.id}), or
     *                                            {@code null} for legacy/unresolved rows. Unlike
     *                                            {@code repositoryNameWithOwner} it never changes across a
     *                                            rename/transfer, so the sync engine uses it to re-key the
     *                                            monitor and to decide that a name-404 is a rename (heal),
     *                                            not a deletion (remove).
     */
    record SyncTarget(
            Long id,
            Long scopeId,
            @org.jspecify.annotations.Nullable Long installationId,
            @org.jspecify.annotations.Nullable String personalAccessToken,
            AuthMode authMode,
            String repositoryNameWithOwner,
            @org.jspecify.annotations.Nullable Instant lastLabelsSyncedAt,
            @org.jspecify.annotations.Nullable Instant lastMilestonesSyncedAt,
            @org.jspecify.annotations.Nullable Instant lastIssuesSyncedAt,
            @org.jspecify.annotations.Nullable Instant lastPullRequestsSyncedAt,
            @org.jspecify.annotations.Nullable Instant lastDiscussionsSyncedAt,
            @org.jspecify.annotations.Nullable Instant lastCollaboratorsSyncedAt,
            @org.jspecify.annotations.Nullable Instant lastFullSyncAt,
            @org.jspecify.annotations.Nullable Integer issueBackfillHighWaterMark,
            @org.jspecify.annotations.Nullable Integer issueBackfillCheckpoint,
            @org.jspecify.annotations.Nullable Integer pullRequestBackfillHighWaterMark,
            @org.jspecify.annotations.Nullable Integer pullRequestBackfillCheckpoint,
            @org.jspecify.annotations.Nullable Instant backfillLastRunAt,
            @org.jspecify.annotations.Nullable String issueSyncCursor,
            @org.jspecify.annotations.Nullable String pullRequestSyncCursor,
            @org.jspecify.annotations.Nullable String discussionSyncCursor,
            @org.jspecify.annotations.Nullable Long nativeId) {
        /** @return true if full sync has never run or is older than {@code staleThreshold} */
        public boolean needsFullSync(Instant staleThreshold) {
            return lastFullSyncAt == null || lastFullSyncAt.isBefore(staleThreshold);
        }

        /** @return true if labels have never been synced or are older than {@code staleThreshold} */
        public boolean needsLabelSync(Instant staleThreshold) {
            return lastLabelsSyncedAt == null || lastLabelsSyncedAt.isBefore(staleThreshold);
        }

        /** @return true if issue backfill has been initialized (high water mark captured) */
        public boolean isIssueBackfillInitialized() {
            return issueBackfillHighWaterMark != null;
        }

        /** @return true if pull request backfill has been initialized (high water mark captured) */
        public boolean isPullRequestBackfillInitialized() {
            return pullRequestBackfillHighWaterMark != null;
        }

        public boolean isBackfillInitialized() {
            return isIssueBackfillInitialized() || isPullRequestBackfillInitialized();
        }

        public boolean isIssueBackfillComplete() {
            return (isIssueBackfillInitialized()
                    && (Integer.valueOf(0).equals(issueBackfillHighWaterMark)
                            || (issueBackfillCheckpoint != null && issueBackfillCheckpoint <= 0)));
        }

        public boolean isPullRequestBackfillComplete() {
            return (isPullRequestBackfillInitialized()
                    && (Integer.valueOf(0).equals(pullRequestBackfillHighWaterMark)
                            || (pullRequestBackfillCheckpoint != null && pullRequestBackfillCheckpoint <= 0)));
        }

        public boolean isBackfillComplete() {
            return isIssueBackfillComplete() && isPullRequestBackfillComplete();
        }

        /** @return number of issues remaining to backfill */
        public int getIssueBackfillRemaining() {
            if (issueBackfillHighWaterMark == null || issueBackfillHighWaterMark == 0) return 0;
            if (issueBackfillCheckpoint == null) return issueBackfillHighWaterMark;
            return Math.max(0, issueBackfillCheckpoint);
        }

        /** @return number of pull requests remaining to backfill */
        public int getPullRequestBackfillRemaining() {
            if (pullRequestBackfillHighWaterMark == null || pullRequestBackfillHighWaterMark == 0) return 0;
            if (pullRequestBackfillCheckpoint == null) return pullRequestBackfillHighWaterMark;
            return Math.max(0, pullRequestBackfillCheckpoint);
        }

        public int getBackfillRemaining() {
            return getIssueBackfillRemaining() + getPullRequestBackfillRemaining();
        }
    }

    /**
     * User sync state for a scope.
     *
     * @param scopeId       unique scope identifier
     * @param usersSyncedAt last users sync timestamp
     */
    record UserSyncState(
            Long scopeId, @org.jspecify.annotations.Nullable Instant usersSyncedAt) {
        private static final long SECONDS_PER_MINUTE = 60L;

        public boolean needsSync(int cooldownMinutes) {
            return (usersSyncedAt == null
                    || usersSyncedAt.isBefore(Instant.now().minusSeconds(cooldownMinutes * SECONDS_PER_MINUTE)));
        }
    }

    /**
     * Team sync state for a scope.
     *
     * @param scopeId           unique scope identifier
     * @param teamsSyncedAt     last teams sync timestamp
     * @param organizationNames GitHub organizations to sync teams from
     */
    record TeamSyncState(
            Long scopeId, @org.jspecify.annotations.Nullable Instant teamsSyncedAt, List<String> organizationNames) {
        private static final long SECONDS_PER_MINUTE = 60L;

        public boolean needsSync(int cooldownMinutes) {
            return (teamsSyncedAt == null
                    || teamsSyncedAt.isBefore(Instant.now().minusSeconds(cooldownMinutes * SECONDS_PER_MINUTE)));
        }
    }

    enum SyncPass {
        RECENT,
        HISTORICAL_BACKFILL,
    }

    /**
     * Types of synchronization operations tracked by the system.
     */
    enum SyncType {
        /** Repository labels sync */
        LABELS,
        /** Repository milestones sync */
        MILESTONES,
        /** Issues incremental sync */
        ISSUES,
        /** Pull requests incremental sync */
        PULL_REQUESTS,
        /** Discussions incremental sync */
        DISCUSSIONS,
        /** Repository collaborators sync */
        COLLABORATORS,
        /** Full repository metadata sync */
        FULL_REPOSITORY,
        /** Organization-level issue types sync */
        ISSUE_TYPES,
        /** Issue blocking/blocked-by relationships sync */
        ISSUE_DEPENDENCIES,
        /** Issue parent/child hierarchy sync */
        SUB_ISSUES,
    }
}
