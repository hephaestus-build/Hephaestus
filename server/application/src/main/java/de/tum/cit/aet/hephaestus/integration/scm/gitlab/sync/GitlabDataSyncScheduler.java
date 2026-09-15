package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuedependency.GitLabIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.GitLabMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupMemberSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue.GitLabSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for periodic GitLab data synchronization across all GitLab scopes.
 *
 * <h2>Purpose</h2>
 * Provides eventual consistency for GitLab data by running scheduled re-sync jobs.
 * This closes the critical gap where missed webhooks (restarts, NATS outages,
 * GitLab's 40-failure auto-disable) cause permanent data drift.
 *
 * <h2>Architecture</h2>
 * Mirrors {@link GithubDataSyncScheduler} but uses GitLab-specific sync services.
 * Uses SPI interfaces to remain decoupled from consuming modules:
 * <ul>
 *   <li>{@link SyncTargetProvider} - provides scope/repository info to sync</li>
 *   <li>{@link SyncContextProvider} - manages context for logging and isolation</li>
 * </ul>
 * Runs on the same cron schedule, processing all active GitLab workspaces in parallel.
 * Each workspace syncs: group projects, memberships, labels, milestones, issues,
 * merge requests, and teams.
 *
 * <h2>Thread Safety</h2>
 * Same guarantees as {@link GithubDataSyncScheduler}: single-threaded scheduling,
 * parallel workspace processing via virtual threads, per-workspace error isolation.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitlabDataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitlabDataSyncScheduler.class);

    private final SyncTargetProvider syncTargetProvider;
    private final SyncContextProvider syncContextProvider;
    private final OrganizationRepository organizationRepository;
    private final RepositoryRepository repositoryRepository;
    private final ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider;
    private final ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final Executor monitoringExecutor;
    private final ConnectionRepository connectionRepository;
    private final SyncJobService syncJobService;
    private final GitLabDeletionSweepService deletionSweepService;

    public GitlabDataSyncScheduler(
            SyncTargetProvider syncTargetProvider,
            SyncContextProvider syncContextProvider,
            OrganizationRepository organizationRepository,
            RepositoryRepository repositoryRepository,
            ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider,
            ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider,
            SyncSchedulerProperties syncSchedulerProperties,
            @Qualifier("monitoringExecutor") Executor monitoringExecutor,
            ConnectionRepository connectionRepository,
            SyncJobService syncJobService,
            GitLabDeletionSweepService deletionSweepService) {
        this.syncTargetProvider = syncTargetProvider;
        this.syncContextProvider = syncContextProvider;
        this.organizationRepository = organizationRepository;
        this.repositoryRepository = repositoryRepository;
        this.syncServiceHolderProvider = syncServiceHolderProvider;
        this.rateLimitTrackerProvider = rateLimitTrackerProvider;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.monitoringExecutor = monitoringExecutor;
        this.connectionRepository = connectionRepository;
        this.syncJobService = syncJobService;
        this.deletionSweepService = deletionSweepService;
    }

    @PostConstruct
    void logSyncConfiguration() {
        log.info(
                "GitLab incremental sync config: cron={} (syncs groups, memberships, labels, milestones, issues, MRs, teams)",
                syncSchedulerProperties.cron());
    }

    /**
     * Scheduled job to sync GitLab data for all active GitLab workspaces.
     * Runs on the same cron as GitHub sync (default: daily at 3 AM).
     */
    @Scheduled(cron = "${hephaestus.sync.cron}")
    @SchedulerLock(name = "gitlab-data-sync", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void syncDataCron() {
        List<SyncSession> sessions = syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB);

        if (sessions.isEmpty()) {
            log.debug("No active GitLab workspaces to sync");
            return;
        }

        log.info("Starting scheduled GitLab sync: workspaceCount={}", sessions.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        CompletableFuture<?>[] futures = sessions.stream()
                .map(session -> CompletableFuture.runAsync(() -> syncScopeWithJobRecording(session), monitoringExecutor)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                failureCount.incrementAndGet();
                            } else {
                                successCount.incrementAndGet();
                            }
                        }))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).get();
            log.info(
                    "Completed scheduled GitLab sync: workspaceCount={}, successful={}, failed={}",
                    sessions.size(),
                    successCount.get(),
                    failureCount.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "GitLab sync interrupted: workspaceCount={}, successful={}, failed={}",
                    sessions.size(),
                    successCount.get(),
                    failureCount.get());
        } catch (ExecutionException e) {
            log.error("Unexpected error during scheduled GitLab sync", e);
        }
    }

    /**
     * Run the same warning-aware body used by the scheduled path for one manual job.
     *
     * <p>{@code type} is forwarded rather than dropped: it is what decides whether the body ends with a
     * deletion sweep, and it is the only difference between an {@code INITIAL} and a
     * {@code RECONCILIATION} run.
     */
    public void syncWorkspaceNow(long workspaceId, SyncExecutionHandle handle, SyncJobType type) {
        SyncSession session = syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB).stream()
                .filter(candidate -> candidate.scopeId().equals(workspaceId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("No active GitLab sync scope for workspace " + workspaceId));
        syncScope(session, handle, type);
    }

    /**
     * Wraps {@link #syncScope} in the {@code SyncJobService} template when the scope has an ACTIVE
     * GitLab {@link Connection} — the cron's per-scope fan-out is the {@code SCHEDULED}/
     * {@code RECONCILIATION} trigger path. A scope without an ACTIVE
     * connection (e.g. a stale sync target left over from a disconnected workspace) still syncs,
     * just without a job row — the sync-target enumeration and the connection registry aren't
     * perfectly aligned today, and skipping the sync entirely would be a regression versus current
     * behavior.
     *
     * <p>A {@link SyncJobConflictException} (this connection already has an active job — most
     * plausibly a manual "Sync now" the admin triggered) skips this scope's cron run entirely rather
     * than racing it; the manual job's own completion will bring watermarks current.
     */
    private void syncScopeWithJobRecording(SyncSession session) {
        Optional<Connection> connection =
                connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        session.scopeId(), IntegrationKind.GITLAB, IntegrationState.ACTIVE);
        if (connection.isEmpty()) {
            syncScope(session);
            return;
        }

        try {
            syncJobService.run(
                    new SyncJobRequest(
                            session.scopeId(),
                            connection.get().getId(),
                            IntegrationKind.GITLAB,
                            SyncJobType.RECONCILIATION,
                            SyncJobTrigger.SCHEDULED,
                            null),
                    handle -> syncScope(session, handle, SyncJobType.RECONCILIATION));
        } catch (SyncJobConflictException e) {
            log.info(
                    "Skipped scheduled GitLab sync, already an active sync job: scopeId={}, connectionId={}",
                    session.scopeId(),
                    connection.get().getId());
        }
    }

    /** Unrecorded fallback (no ACTIVE connection to attach a job to): run to completion, no handle. */
    private void syncScope(SyncSession session) {
        // The unrecorded cron fallback is still a reconciliation pass — sweep deletions like the recorded one.
        syncScope(session, null, SyncJobType.RECONCILIATION);
    }

    /**
     * Runs one workspace's full GitLab reconcile. When invoked through a {@link SyncJobService} job the
     * caller threads the live {@link SyncExecutionHandle} so the per-repository phase reports coarse
     * repos-done/repos-total progress and honors a cooperative cancel between repositories — parity with
     * the SCM manual runner. A cancel observed during the repo phase skips the remaining post-repo/team
     * phases. The unrecorded fallback passes {@code null} and simply runs to completion.
     */
    private void syncScope(SyncSession session, @Nullable SyncExecutionHandle handle, SyncJobType type) {
        Long scopeId = session.scopeId();
        String safeLogin = sanitizeForLog(session.accountLogin());

        syncContextProvider.setContext(session.syncContext());
        try {
            log.info("Starting GitLab workspace sync: scopeId={}, accountLogin={}", scopeId, safeLogin);

            GitLabSyncServiceHolder services = syncServiceHolderProvider.getIfAvailable();
            if (services == null) {
                log.warn("GitLab sync services unavailable, skipping: scopeId={}", scopeId);
                reportWarning(handle);
                return;
            }

            if (session.accountLogin() == null || session.accountLogin().isBlank()) {
                log.warn("No accountLogin for GitLab workspace, skipping: scopeId={}", scopeId);
                reportWarning(handle);
                return;
            }

            // Phase 1: Sync group projects (discovers new repos, removes deleted ones)
            syncGroupProjects(services, session, handle);

            // Phase 2: Sync group memberships
            syncGroupMembers(services, session, handle);

            // Phase 2.5: Sync issue types (org-level, before per-repo sync)
            syncIssueTypes(services, session, handle);

            // Phase 2.6: Sync group milestones (fan-out to every repo in the group).
            // Must run before the per-repo sync so the issue-sync phase can resolve
            // milestone references on issues by iid.
            syncGroupMilestones(services, session, handle);

            // Phase 3: Per-repository sync (labels, milestones, issues, MRs, collaborators) —
            // the dominant-cost phase, where cancel/progress are threaded through the handle.
            syncRepositories(services, session, handle);

            // A cancel observed during the repo phase skips the remaining phases (cooperative best-effort).
            if (handle == null || !handle.isCancellationRequested()) {
                // Phase 4: Post-repo sync (sub-issues, dependencies — needs issues to exist)
                syncPostRepo(services, session, handle);

                // Phase 5: Sync teams (subgroups)
                syncTeams(services, session, handle);

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

            log.info("Completed GitLab workspace sync: scopeId={}, accountLogin={}", scopeId, safeLogin);
        } catch (Exception e) {
            log.error("Failed GitLab workspace sync: scopeId={}, accountLogin={}", scopeId, safeLogin, e);
            if (handle != null) {
                throw new IllegalStateException("Failed to sync GitLab scope " + scopeId, e);
            }
        } finally {
            syncContextProvider.clearContext();
        }
    }

    private void syncGroupProjects(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabGroupSyncService groupSync = services.getGroupSyncService();
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

            // Stale repo cleanup: only when sync completed normally
            if (result.status() == GitLabSyncResult.Status.COMPLETED) {
                removeStaleRepositories(session, result);
            } else {
                reportWarning(handle);
            }
        } catch (Exception e) {
            log.error("Failed GitLab group project sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    /**
     * Removes repositories that exist in the database but were not found during
     * the latest group project sync. Guards against false positives by only running
     * when the sync completed fully (all pages fetched).
     */
    private void removeStaleRepositories(SyncSession session, GitLabSyncResult result) {
        Long providerId = getGitLabProviderId(session.accountLogin());
        if (providerId == null) return;

        Set<Long> syncedNativeIds =
                result.synced().stream().map(Repository::getNativeId).collect(Collectors.toSet());

        List<Repository> existingRepos = repositoryRepository.findAllByOrganization_LoginIgnoreCaseAndProviderId(
                session.accountLogin(), providerId);

        int removed = 0;
        for (Repository repo : existingRepos) {
            if (repo.getProvider() != null
                    && Objects.requireNonNull(repo.getProvider().getId()).equals(providerId)
                    && !syncedNativeIds.contains(repo.getNativeId())) {
                log.info(
                        "Removing stale repository: repoId={}, name={}, nativeId={}",
                        repo.getId(),
                        sanitizeForLog(repo.getNameWithOwner()),
                        repo.getNativeId());
                repositoryRepository.delete(repo);
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Removed stale repositories: scopeId={}, count={}", session.scopeId(), removed);
        }
    }

    /**
     * Heals monitors whose {@code name_with_owner} went stale after an upstream rename/transfer.
     * <p>
     * The group-project sync (which runs earlier in the cycle) upserts the domain {@link Repository}
     * by its stable native id, so a renamed project's row already carries the new path while the
     * {@code RepositoryToMonitor} still holds the old one — the name-keyed monitor join and the NATS
     * subject filter both then miss it. For each sync target we resolve the domain repository by its
     * native id (falling back to name for legacy rows that predate the id column, to capture it once)
     * and hand the current identity to the SPI, which re-keys the monitor and refreshes the consumer.
     * Best-effort: a per-target failure never aborts the sync.
     */
    private void reconcileMonitorIdentities(SyncSession session) {
        Long providerId = getGitLabProviderId(session.accountLogin());
        if (providerId == null) {
            return;
        }
        for (SyncTarget target : session.syncTargets()) {
            try {
                Repository repo = null;
                if (target.nativeId() != null) {
                    repo = repositoryRepository
                            .findByNativeIdAndProviderId(target.nativeId(), providerId)
                            .orElse(null);
                }
                if (repo == null) {
                    // Legacy row with no captured id yet — resolve by (still-current) name to capture it.
                    repo = repositoryRepository
                            .findByNameWithOwnerAndProviderId(target.repositoryNameWithOwner(), providerId)
                            .orElse(null);
                }
                if (repo != null && repo.getNativeId() != null) {
                    syncTargetProvider.reconcileSyncTargetIdentity(
                            target.id(), repo.getNativeId(), repo.getNameWithOwner());
                }
            } catch (Exception e) {
                log.debug("Skipped monitor identity reconcile: syncTargetId={}, error={}", target.id(), e.getMessage());
            }
        }
    }

    private void syncGroupMembers(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabGroupMemberSyncService memberSync = services.getGroupMemberSyncService();
        if (memberSync == null) return;

        try {
            organizationRepository
                    .findByLoginIgnoreCaseAndProvider_Type(session.accountLogin(), IdentityProviderType.GITLAB)
                    .ifPresent(org -> {
                        int count = memberSync.syncGroupMemberships(session.scopeId(), session.accountLogin(), org);
                        log.info("GitLab membership sync: scopeId={}, membersSynced={}", session.scopeId(), count);
                    });
        } catch (Exception e) {
            log.error("Failed GitLab membership sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private void syncIssueTypes(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabIssueTypeSyncService issueTypeSync = services.getIssueTypeSyncService();
        if (issueTypeSync == null) return;

        try {
            int count = issueTypeSync.syncIssueTypesForGroup(session.scopeId(), session.accountLogin());
            log.info("GitLab issue type sync: scopeId={}, types={}", session.scopeId(), count);
        } catch (Exception e) {
            log.error("Failed GitLab issue type sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    /**
     * Syncs group milestones (including ancestors and descendant subgroups) and fans them out
     * to every monitored repository in this workspace. Closes the gap where
     * {@code project.milestones(includeAncestors: true)} did not reliably surface group
     * milestones, leaving the {@code milestone} table largely empty on fresh syncs.
     */
    private void syncGroupMilestones(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabMilestoneSyncService milestoneSync = services.getMilestoneSyncService();
        if (milestoneSync == null) return;

        if (session.accountLogin() == null || session.accountLogin().isBlank()) {
            return;
        }

        List<Repository> repos = repositoryRepository.findAllByWorkspaceMonitors(session.scopeId());
        if (repos.isEmpty()) {
            log.debug("No repositories for group milestone sync: scopeId={}", session.scopeId());
            return;
        }

        try {
            SyncResult result = milestoneSync.syncMilestonesForGroup(session.scopeId(), session.accountLogin(), repos);
            log.info(
                    "GitLab group milestone sync: scopeId={}, group={}, written={}",
                    session.scopeId(),
                    sanitizeForLog(session.accountLogin()),
                    result.count());
        } catch (Exception e) {
            log.error(
                    "Failed GitLab group milestone sync: scopeId={}, group={}",
                    session.scopeId(),
                    sanitizeForLog(session.accountLogin()),
                    e);
            reportWarning(handle);
        }
    }

    private void syncRepositories(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabLabelSyncService labelSync = services.getLabelSyncService();
        GitLabMilestoneSyncService milestoneSync = services.getMilestoneSyncService();
        GitLabIssueSyncService issueSync = services.getIssueSyncService();
        GitLabMergeRequestSyncService mrSync = services.getMergeRequestSyncService();
        GitLabCollaboratorSyncService collaboratorSync = services.getCollaboratorSyncService();
        GitLabCommitSyncService commitSync = services.getCommitSyncService();
        var commitBackfill = services.getCommitBackfillService();
        var commitMrLinker = services.getCommitMergeRequestLinker();

        // Re-key any monitor whose name_with_owner went stale after an upstream rename/transfer BEFORE
        // the name-keyed join below, so a renamed project re-enters the sync set and its NATS filter is
        // rebuilt in the same cycle instead of silently dropping out. The domain Repository row was
        // already healed to the new path by the group-project sync (keyed on the stable native id).
        reconcileMonitorIdentities(session);

        // Find all repositories monitored by this workspace (via RepositoryToMonitor join,
        // which correctly includes subgroup repos — not just top-level group repos)
        List<Repository> repos = repositoryRepository.findAllByWorkspaceMonitors(session.scopeId());

        if (repos.isEmpty()) {
            log.debug("No repositories to sync for GitLab workspace: scopeId={}", session.scopeId());
            return;
        }

        // Map nameWithOwner → sync target id from the session so each phase can write
        // its per-repo watermark via the SPI without reaching into workspace internals.
        Map<String, Long> syncTargetIdsByNameWithOwner = session.syncTargets().stream()
                .collect(Collectors.toMap(SyncTarget::repositoryNameWithOwner, SyncTarget::id, (a, b) -> a));

        GitLabRateLimitTracker rateLimitTracker = rateLimitTrackerProvider.getIfAvailable();
        int totalLabels = 0,
                totalMilestones = 0,
                totalIssues = 0,
                totalMRs = 0,
                totalCollaborators = 0,
                totalCommits = 0;
        int reposProcessed = 0;
        int totalRepos = repos.size();

        for (Repository repo : repos) {
            // Cooperative cancel checkpoint between repositories (matches the manual runner): a cancelled
            // job stops before the next repo rather than mid-repository.
            if (handle != null && handle.isCancellationRequested()) {
                log.info(
                        "GitLab scheduled sync cancelled between repositories: scopeId={}, reposProcessed={}, reposRemaining={}",
                        session.scopeId(),
                        reposProcessed,
                        totalRepos - reposProcessed);
                break;
            }

            // Wait for rate limit if critical
            if (rateLimitTracker != null && rateLimitTracker.isCritical(session.scopeId())) {
                log.info(
                        "Rate limit critical, waiting: scopeId={}, remaining={}",
                        session.scopeId(),
                        rateLimitTracker.getRemaining(session.scopeId()));
                try {
                    rateLimitTracker.waitIfNeeded(session.scopeId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Rate limit wait interrupted, stopping repo sync: scopeId={}", session.scopeId());
                    reportWarning(handle);
                    break;
                }
            }

            // Capture updatedAfter ONCE per repo before any sync phase
            OffsetDateTime updatedAfter = null;
            if (repo.getLastSyncAt() != null) {
                Instant buffered = repo.getLastSyncAt().minus(Duration.ofMinutes(5));
                updatedAfter = buffered.atOffset(ZoneOffset.UTC);
            }

            // Look up the sync-target id from the session so each phase can write its
            // per-repo watermark via the SPI. Not all repositories have an entry in the
            // session (edge case), in which case we skip the watermark writes silently.
            Long rtmId = syncTargetIdsByNameWithOwner.get(repo.getNameWithOwner());

            boolean issuesDone = false;
            boolean mrsDone = false;

            // Labels
            if (labelSync != null) {
                try {
                    SyncResult r = labelSync.syncLabelsForRepository(session.scopeId(), repo);
                    totalLabels += r.count();
                    if (rtmId != null) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.LABELS, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn("Failed label sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    reportWarning(handle);
                }
            }

            // Milestones
            if (milestoneSync != null) {
                try {
                    SyncResult r = milestoneSync.syncMilestonesForRepository(session.scopeId(), repo);
                    totalMilestones += r.count();
                    if (rtmId != null) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.MILESTONES, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed milestone sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    reportWarning(handle);
                }
            }

            // Issues
            if (issueSync != null) {
                try {
                    SyncResult r = issueSync.syncIssues(session.scopeId(), repo, updatedAfter);
                    totalIssues += r.count();
                    issuesDone = r.isCompleted();
                    if (!issuesDone) {
                        reportWarning(handle);
                    }
                    if (rtmId != null && issuesDone) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.ISSUES, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn("Failed issue sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    reportWarning(handle);
                }
            }

            // Merge Requests
            if (mrSync != null) {
                try {
                    SyncResult r = mrSync.syncMergeRequests(session.scopeId(), repo, updatedAfter);
                    totalMRs += r.count();
                    mrsDone = r.isCompleted();
                    if (!mrsDone) {
                        reportWarning(handle);
                    }
                    if (rtmId != null && mrsDone) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.PULL_REQUESTS, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn("Failed MR sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    reportWarning(handle);
                }
            }

            // Collaborators
            if (collaboratorSync != null) {
                try {
                    SyncResult r = collaboratorSync.syncCollaboratorsForRepository(session.scopeId(), repo);
                    totalCollaborators += r.count();
                    if (rtmId != null) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.COLLABORATORS, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed collaborator sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    reportWarning(handle);
                }
            }

            // Commits — the native Git backfill provides diff stats and file changes; the REST sync
            // runs only when no backfill bean exists.
            if (commitBackfill != null) {
                try {
                    SyncResult r = commitBackfill.backfillCommits(session.scopeId(), repo);
                    totalCommits += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed commit backfill: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    reportWarning(handle);
                }
            } else if (commitSync != null) {
                try {
                    SyncResult r = commitSync.syncCommitsForRepository(session.scopeId(), repo, updatedAfter);
                    totalCommits += r.count();
                } catch (Exception e) {
                    log.warn("Failed commit sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    reportWarning(handle);
                }
            }

            // Update lastSyncAt only when all enabled phases completed
            boolean allDone = (issueSync == null || issuesDone) && (mrSync == null || mrsDone);
            if (allDone) {
                repositoryRepository.updateLastSyncAt(repo.getId(), Instant.now());
                if (rtmId != null) {
                    syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.FULL_REPOSITORY, Instant.now());
                }
            }

            reposProcessed++;
            if (handle != null) {
                handle.progress(
                        reposProcessed,
                        totalRepos,
                        // Just the repository — "N of M" is already the progress bar's own reading
                        // (unitsCompleted/unitsTotal travel on the same record).
                        SyncProgress.ofResource(
                                SyncPhase.REPOSITORIES,
                                "Syncing " + repo.getNameWithOwner(),
                                repo.getNameWithOwner(),
                                reposProcessed,
                                totalRepos));
            }
        }

        // Second pass: link commits to MRs. Must run after every repo has finished syncing so
        // a commit whose SHA appears on an MR in a sibling repo can still be linked (the target MR
        // repo may not have synced its MRs when this repo's commits were fetched).
        if (commitMrLinker != null && (handle == null || !handle.isCancellationRequested())) {
            for (Repository repo : repos) {
                OffsetDateTime repoUpdatedAfter = null;
                if (repo.getLastSyncAt() != null) {
                    Instant buffered = repo.getLastSyncAt().minus(Duration.ofMinutes(5));
                    repoUpdatedAfter = buffered.atOffset(ZoneOffset.UTC);
                }
                try {
                    commitMrLinker.linkCommits(session.scopeId(), repo, repoUpdatedAfter);
                } catch (Exception e) {
                    log.warn(
                            "Failed commit→MR linking (second pass): scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    reportWarning(handle);
                }
            }
        }

        log.info(
                "GitLab repo sync complete: scopeId={}, repos={}, labels={}, milestones={}, issues={}, mrs={}, collaborators={}, commits={}",
                session.scopeId(),
                repos.size(),
                totalLabels,
                totalMilestones,
                totalIssues,
                totalMRs,
                totalCollaborators,
                totalCommits);
    }

    private void syncPostRepo(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabSubIssueSyncService subIssueSync = services.getSubIssueSyncService();
        GitLabIssueDependencySyncService depSync = services.getIssueDependencySyncService();

        if (subIssueSync == null && depSync == null) return;

        List<Repository> repos = repositoryRepository.findAllByWorkspaceMonitors(session.scopeId());

        int totalSubIssues = 0, totalDeps = 0;

        for (Repository repo : repos) {
            if (subIssueSync != null) {
                try {
                    SyncResult r = subIssueSync.syncSubIssuesForRepository(session.scopeId(), repo);
                    totalSubIssues += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed sub-issue sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    reportWarning(handle);
                }
            }
            if (depSync != null) {
                try {
                    SyncResult r = depSync.syncDependenciesForRepository(session.scopeId(), repo);
                    totalDeps += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed dependency sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    reportWarning(handle);
                }
            }
        }

        if (totalSubIssues > 0 || totalDeps > 0) {
            log.info(
                    "GitLab post-repo sync: scopeId={}, subIssues={}, dependencies={}",
                    session.scopeId(),
                    totalSubIssues,
                    totalDeps);
        }
    }

    private void syncTeams(
            GitLabSyncServiceHolder services, SyncSession session, @Nullable SyncExecutionHandle handle) {
        GitLabTeamSyncService teamSync = services.getTeamSyncService();
        if (teamSync == null) return;

        try {
            int count = teamSync.syncTeamsForGroup(session.scopeId(), session.accountLogin());
            syncTargetProvider.updateTeamsSyncTimestamp(session.scopeId(), Instant.now());
            log.info("GitLab team sync: scopeId={}, teams={}", session.scopeId(), count);
        } catch (Exception e) {
            log.error("Failed GitLab team sync: scopeId={}", session.scopeId(), e);
            reportWarning(handle);
        }
    }

    private static void reportWarning(@Nullable SyncExecutionHandle handle) {
        if (handle != null) {
            handle.reportWarnings();
        }
    }

    /**
     * Resolves the GitLab provider ID by looking up the organization.
     */
    private @Nullable Long getGitLabProviderId(String accountLogin) {
        return organizationRepository
                .findByLoginIgnoreCaseAndProvider_Type(accountLogin, IdentityProviderType.GITLAB)
                .map(org -> org.getProvider() != null ? org.getProvider().getId() : null)
                .orElse(null);
    }
}
