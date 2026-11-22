package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupMemberSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSelectedMembershipProjection;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRepositoryMonitors;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs one GitLab scope's full sync in its phase order: the missing group webhook, the group's projects, its
 * memberships and issue types, the repository phases of {@link GitLabRepositorySync}, the teams, the members of
 * selected projects, and, on a {@code RECONCILIATION}, the deletion sweep. Each group phase calls its own entity sync
 * service; a {@code null} one skips its phase. {@link GitLabDataSyncScheduler} runs it for each scope on the cron; a
 * manual job runs it through {@link #syncWorkspaceNow}.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabScopeSync {

    private static final Logger log = LoggerFactory.getLogger(GitLabScopeSync.class);

    private final SyncTargetProvider syncTargetProvider;
    private final SyncContextProvider syncContextProvider;
    private final GitLabDeletionSweepService deletionSweepService;
    private final GitLabWorkspaceInitializationService initializationService;
    private final GitLabRepositoryMonitors repositoryMonitors;
    private final WorkspaceLookup workspaceLookup;
    private final GitLabRepositorySync repositorySync;
    private final @Nullable GitLabGroupSyncService groupSync;
    private final @Nullable GitLabGroupMemberSyncService groupMemberSync;
    private final @Nullable GitLabIssueTypeSyncService issueTypeSync;
    private final @Nullable GitLabTeamSyncService teamSync;
    private final @Nullable GitLabSelectedMembershipProjection selectedMembershipProjection;

    public GitLabScopeSync(
            SyncTargetProvider syncTargetProvider,
            SyncContextProvider syncContextProvider,
            GitLabDeletionSweepService deletionSweepService,
            GitLabWorkspaceInitializationService initializationService,
            GitLabRepositoryMonitors repositoryMonitors,
            WorkspaceLookup workspaceLookup,
            GitLabRepositorySync repositorySync,
            @Nullable GitLabGroupSyncService groupSync,
            @Nullable GitLabGroupMemberSyncService groupMemberSync,
            @Nullable GitLabIssueTypeSyncService issueTypeSync,
            @Nullable GitLabTeamSyncService teamSync,
            @Nullable GitLabSelectedMembershipProjection selectedMembershipProjection) {
        this.syncTargetProvider = syncTargetProvider;
        this.syncContextProvider = syncContextProvider;
        this.deletionSweepService = deletionSweepService;
        this.initializationService = initializationService;
        this.repositoryMonitors = repositoryMonitors;
        this.workspaceLookup = workspaceLookup;
        this.repositorySync = repositorySync;
        this.groupSync = groupSync;
        this.groupMemberSync = groupMemberSync;
        this.issueTypeSync = issueTypeSync;
        this.teamSync = teamSync;
        this.selectedMembershipProjection = selectedMembershipProjection;
    }

    /**
     * Run the same warning-aware body used by the scheduled path for one manual job.
     *
     * <p>{@code type} is forwarded rather than dropped: it is what decides whether the body ends with a
     * deletion sweep, and it is the only difference between an {@code INITIAL} and a
     * {@code RECONCILIATION} run.
     */
    public void syncWorkspaceNow(long workspaceId, SyncExecutionHandle handle, SyncJobType type) {
        SyncSession session = syncTargetProvider
                .getSyncSession(workspaceId, IntegrationKind.GITLAB)
                .orElseThrow(() -> new IllegalStateException("No GitLab sync scope for workspace "
                        + workspaceId
                        + ": it is not active, or its provider refused its credential"));
        run(session, handle, type);
    }

    /**
     * Runs one workspace's full GitLab reconcile. When invoked through a {@link SyncJobService} job the
     * caller threads the live {@link SyncExecutionHandle} so the per-repository phase reports coarse
     * repos-done/repos-total progress and honors a cooperative cancel between repositories — parity with
     * the SCM manual runner. A cancel observed during the repo phase skips the remaining post-repo/team
     * phases. The unrecorded fallback passes {@code null} and simply runs to completion.
     */
    void run(SyncSession session, @Nullable SyncExecutionHandle handle, SyncJobType type) {
        Long scopeId = session.scopeId();
        syncContextProvider.setContext(session.syncContext());
        try {
            log.info("Starting GitLab workspace sync: scopeId={}", scopeId);

            if (session.accountLogin() == null || session.accountLogin().isBlank()) {
                log.warn("No accountLogin for GitLab workspace, skipping: scopeId={}", scopeId);
                reportWarning(handle);
                return;
            }

            // Phase 0: Open a group webhook an earlier initialization had to leave closed
            openMissingWebhook(session, handle);

            // Phase 1: Sync group projects (discovers new repositories and heals their identities)
            syncGroupProjects(session, handle);

            // Phase 2: Sync group memberships
            syncGroupMembers(session, handle);

            // Phase 2.5: Sync issue types (org-level, before per-repo sync)
            syncIssueTypes(session, handle);

            // Phase 2.6: Sync group milestones (fan-out to every repo in the group).
            // Must run before the per-repo sync so the issue-sync phase can resolve
            // milestone references on issues by iid.
            repositorySync.syncGroupMilestones(session, handle);

            // Phase 3: Per-repository sync (labels, milestones, issues, MRs, collaborators) —
            // the dominant-cost phase, where cancel/progress are threaded through the handle.
            Map<Long, String> resourceErrors = new HashMap<>();
            Map<Long, GitLabProjectAdmission.ProjectRead> reads = new HashMap<>();
            List<Repository> availableRepos = repositorySync.syncRepositories(session, handle, resourceErrors, reads);
            if (!syncTargetProvider.isScopeActiveForSync(scopeId)) {
                log.info("Stopped GitLab workspace sync: reason=scopeNotEligible, scopeId={}", scopeId);
                reportWarning(handle);
                return;
            }

            // A cancel observed during the repo phase skips the remaining phases (cooperative best-effort).
            if (handle == null || !handle.isCancellationRequested()) {
                // Phase 4: Post-repo sync (sub-issues, dependencies — needs issues to exist)
                repositorySync.syncPostRepo(session, handle, resourceErrors, availableRepos, reads);
            }

            resourceErrors.forEach((targetId, error) -> {
                if (error != null || handle == null || !handle.isCancellationRequested()) {
                    syncTargetProvider.updateSyncError(targetId, SyncPass.RECENT, error);
                }
            });

            if (handle == null || !handle.isCancellationRequested()) {
                // Phase 5: Sync teams (subgroups)
                syncTeams(session, handle);

                // Phase 5.5: A workspace that selected its projects takes its members from what they grant
                projectSelectedMembers(session, handle);

                // Phase 6: Deletion sweep — RECONCILIATION only, and the one thing that makes
                // RECONCILIATION different from INITIAL. Every phase above only ever upserts, so an
                // issue or merge request deleted upstream would otherwise survive here forever and keep
                // inflating the per-project counts — and GitLab emits no issue/MR-deletion webhook at
                // all, so there is not even a missed event that could heal it.
                //
                // Not on INITIAL: a mirror still being populated has nothing stale in it, and every row
                // not fetched yet would read as an upstream deletion to a set difference.
                //
                // Last, deliberately: the sweep's set difference is only meaningful against a mirror that
                // has already had this run's upserts applied. Sweeping first would race the fetch and
                // tombstone rows that were about to arrive.
                if (type == SyncJobType.RECONCILIATION) {
                    GitLabDeletionSweepService.SweepOutcome sweep = deletionSweepService.sweepScope(scopeId, handle);
                    if (sweep.skipped()) {
                        // At least one project's upstream listing could not be proven complete, so its
                        // deletions are still outstanding. Surface it rather than reporting a clean
                        // reconciliation that did not fully reconcile.
                        reportWarning(handle);
                    }
                }
            }

            // A still-set cancel flag means the repo phase aborted on a checkpoint; declare it so the job
            // finalizes CANCELLED rather than a false SUCCEEDED.
            if (handle != null && handle.isCancellationRequested()) {
                handle.reportCancelled();
            }

            log.info("Completed GitLab workspace sync: scopeId={}", scopeId);
        } catch (Exception e) {
            if (handle != null) {
                throw new IllegalStateException("Failed to sync GitLab scope " + scopeId, e);
            }
            log.error("Failed GitLab workspace sync: scopeId={}", scopeId, e);
        } finally {
            syncContextProvider.clearContext();
        }
    }

    private void openMissingWebhook(SyncSession session, @Nullable SyncExecutionHandle handle) {
        try {
            if (initializationService.initializeIfWebhookMissing(session.scopeId())) {
                reportWarning(handle);
            }
        } catch (Exception e) {
            log.error("Failed GitLab webhook recovery: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncGroupProjects(SyncSession session, @Nullable SyncExecutionHandle handle) {
        if (groupSync == null) return;

        try {
            GitLabSyncResult result =
                    groupSync.syncGroupProjects(session.scopeId(), session.accountLogin(), session.serverUrl());
            log.info(
                    "GitLab group project sync: scopeId={}, status={}, synced={}, pages={}",
                    session.scopeId(),
                    result.status(),
                    result.synced().size(),
                    result.pagesCompleted());

            // Discover monitors only from a complete listing. Missing projects retain their work.
            if (result.status() == GitLabSyncResult.Status.COMPLETED) {

                // A complete listing also monitors projects created since the workspace was set up.
                workspaceLookup
                        .find(session.scopeId())
                        .ifPresent(workspace -> repositoryMonitors.monitorAllowed(workspace, result.synced()));
            } else {
                reportWarning(handle);
            }
        } catch (Exception e) {
            log.error("Failed GitLab group project sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncGroupMembers(SyncSession session, @Nullable SyncExecutionHandle handle) {
        if (groupMemberSync == null) return;

        try {
            groupMemberSync
                    .connectedOrganization(session.scopeId(), session.accountLogin())
                    .ifPresent(org -> {
                        int count =
                                groupMemberSync.syncGroupMemberships(session.scopeId(), session.accountLogin(), org);
                        log.info("GitLab membership sync: scopeId={}, membersSynced={}", session.scopeId(), count);
                        if (count < 0) {
                            reportWarning(handle);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed GitLab membership sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncIssueTypes(SyncSession session, @Nullable SyncExecutionHandle handle) {
        if (issueTypeSync == null) return;

        try {
            int count = issueTypeSync.syncIssueTypesForGroup(session.scopeId(), session.accountLogin());
            log.info("GitLab issue type sync: scopeId={}, types={}", session.scopeId(), count);
        } catch (Exception e) {
            log.error("Failed GitLab issue type sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncTeams(SyncSession session, @Nullable SyncExecutionHandle handle) {
        if (teamSync == null) return;

        try {
            GitLabTeamSyncService.Result result = teamSync.syncTeamsForGroup(session.scopeId(), session.accountLogin());
            log.info(
                    "GitLab team sync: scopeId={}, teams={}, complete={}",
                    session.scopeId(),
                    result.teams(),
                    result.complete());
            // An incomplete listing keeps the stored graph and leaves the watermark behind, so the next run retries.
            if (result.complete()) {
                syncTargetProvider.updateTeamsSyncTimestamp(session.scopeId(), Instant.now());
            } else {
                reportWarning(handle);
            }
        } catch (Exception e) {
            log.error("Failed GitLab team sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void projectSelectedMembers(SyncSession session, @Nullable SyncExecutionHandle handle) {
        // A cancel may arrive during the team phase, after the outer check.
        if (selectedMembershipProjection == null || (handle != null && handle.isCancellationRequested())) return;

        try {
            GitLabSelectedMembershipProjection.Outcome outcome =
                    selectedMembershipProjection.project(session.scopeId());
            log.info("GitLab selected membership projection: scopeId={}, outcome={}", session.scopeId(), outcome);
            // Nothing changed; the next sync reads again.
            if (outcome == GitLabSelectedMembershipProjection.Outcome.UNKNOWN
                    || outcome == GitLabSelectedMembershipProjection.Outcome.STALE) {
                reportWarning(handle);
            }
        } catch (Exception e) {
            log.error("Failed GitLab selected membership projection: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private static void reportWarning(@Nullable SyncExecutionHandle handle) {
        if (handle != null) {
            handle.reportWarnings();
        }
    }
}
