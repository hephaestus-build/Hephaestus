package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.spi.RecentSyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.RecentWindow;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.core.sync.RecentWindows;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWatermarks;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuedependency.GitLabIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.GitLabMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue.GitLabSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Runs the repository phases of one GitLab scope's sync under project admission: the group milestone fan-out, then
 * each monitored repository's metadata, labels, milestones, issues, merge requests, collaborators and commits, then
 * the commit-to-merge-request link pass and the activity ledger repair, and, after the repository phase, the
 * sub-issues and issue dependencies. Each entity has its own sync service; a {@code null} one skips its step.
 * {@link GitLabScopeSync} calls these phases in its order.
 *
 * <p>GitLab's counterpart of {@code GitHubDataSyncService}.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabRepositorySync {

    private static final Logger log = LoggerFactory.getLogger(GitLabRepositorySync.class);

    private final ActivityLedgerRepair activityLedgerRepair;
    private final SyncTargetProvider syncTargetProvider;
    private final RepositoryRepository repositoryRepository;
    private final @Nullable GitLabRateLimitTracker rateLimitTracker;
    private final WorkspaceActorSelector actorSelector;
    private final GitLabProjectSyncService projectSyncService;
    private final GitLabProjectAdmission admission;
    private final @Nullable GitLabLabelSyncService labelSync;
    private final @Nullable GitLabMilestoneSyncService milestoneSync;
    private final @Nullable GitLabIssueSyncService issueSync;
    private final @Nullable GitLabMergeRequestSyncService mrSync;
    private final @Nullable GitLabCollaboratorSyncService collaboratorSync;
    private final @Nullable GitLabCommitSyncService commitSync;
    private final @Nullable GitLabCommitBackfillService commitBackfill;
    private final @Nullable GitLabCommitMergeRequestLinker commitMrLinker;
    private final @Nullable GitLabSubIssueSyncService subIssueSync;
    private final @Nullable GitLabIssueDependencySyncService issueDependencySync;

    public GitLabRepositorySync(
            SyncTargetProvider syncTargetProvider,
            RepositoryRepository repositoryRepository,
            @Nullable GitLabRateLimitTracker rateLimitTracker,
            WorkspaceActorSelector actorSelector,
            GitLabProjectSyncService projectSyncService,
            ActivityLedgerRepair activityLedgerRepair,
            GitLabProjectAdmission admission,
            @Nullable GitLabLabelSyncService labelSync,
            @Nullable GitLabMilestoneSyncService milestoneSync,
            @Nullable GitLabIssueSyncService issueSync,
            @Nullable GitLabMergeRequestSyncService mrSync,
            @Nullable GitLabCollaboratorSyncService collaboratorSync,
            @Nullable GitLabCommitSyncService commitSync,
            @Nullable GitLabCommitBackfillService commitBackfill,
            @Nullable GitLabCommitMergeRequestLinker commitMrLinker,
            @Nullable GitLabSubIssueSyncService subIssueSync,
            @Nullable GitLabIssueDependencySyncService issueDependencySync) {
        this.syncTargetProvider = syncTargetProvider;
        this.repositoryRepository = repositoryRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.actorSelector = actorSelector;
        this.projectSyncService = projectSyncService;
        this.activityLedgerRepair = activityLedgerRepair;
        this.admission = admission;
        this.labelSync = labelSync;
        this.milestoneSync = milestoneSync;
        this.issueSync = issueSync;
        this.mrSync = mrSync;
        this.collaboratorSync = collaboratorSync;
        this.commitSync = commitSync;
        this.commitBackfill = commitBackfill;
        this.commitMrLinker = commitMrLinker;
        this.subIssueSync = subIssueSync;
        this.issueDependencySync = issueDependencySync;
    }

    /**
     * Heals monitors whose {@code name_with_owner} went stale after an upstream rename/transfer.
     * <p>
     * The group-project sync (which runs earlier in the cycle) upserts the domain {@link Repository}
     * by its stable native id, so a renamed project's row already carries the new path while the
     * {@code RepositoryToMonitor} still holds the old one, so the name-keyed monitor join misses it. For each sync target we resolve the domain repository by its
     * native id (falling back to the name for a monitor without one, which captures it once)
     * and hand the current identity to the SPI, which re-keys the monitor and refreshes the consumer.
     * Best-effort: a per-target failure never aborts the sync.
     */
    private void reconcileMonitorIdentities(SyncSession session) {
        Long providerId = getGitLabProviderId(session.scopeId());
        if (providerId == null) {
            return;
        }
        for (SyncTarget target : session.syncTargets()) {
            try {
                Repository repo = repositoryRepository
                        .findByNativeIdAndProviderId(target.nativeId(), providerId)
                        .orElse(null);
                if (repo != null) {
                    syncTargetProvider.reconcileSyncTargetIdentity(
                            target.id(), repo.getNativeId(), repo.getNameWithOwner());
                }
            } catch (Exception e) {
                log.debug(
                        "Skipped monitor identity reconcile: syncTargetId={}, error={}",
                        target.id(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Syncs group milestones (including ancestors and descendant subgroups) and fans them out
     * to every monitored repository in this workspace. Closes the gap where
     * {@code project.milestones(includeAncestors: true)} did not reliably surface group
     * milestones, leaving the {@code milestone} table largely empty on fresh syncs.
     */
    void syncGroupMilestones(SyncSession session, @Nullable SyncExecutionHandle handle) {
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
            log.info("GitLab group milestone sync: scopeId={}, written={}", session.scopeId(), result.count());
            GitLabSyncReports.warn(
                    handle, result.status(), SyncPhase.ISSUES, "Group milestone sync", session.accountLogin());
        } catch (Exception e) {
            log.error("Failed GitLab group milestone sync: scopeId={}", session.scopeId(), e);
            GitLabSyncReports.warn(
                    handle,
                    SyncResult.Status.ABORTED_ERROR,
                    SyncPhase.ISSUES,
                    "Group milestone sync",
                    session.accountLogin());
        }
    }

    List<Repository> syncRepositories(
            SyncSession session,
            @Nullable SyncExecutionHandle handle,
            GitLabSyncReports.Cancellation cancellation,
            Map<Long, String> resourceErrors,
            Map<Long, GitLabProjectAdmission.ProjectRead> reads,
            SyncJobType type) {
        // Re-key any monitor whose name_with_owner went stale after an upstream rename/transfer BEFORE
        // the name-keyed join below, so a renamed project re-enters the sync set and its provider inbox filter is
        // rebuilt in the same cycle instead of silently dropping out. The domain Repository row was
        // already healed to the new path by the group-project sync (keyed on the stable native id).
        reconcileMonitorIdentities(session);

        // Find all repositories monitored by this workspace (via RepositoryToMonitor join,
        // which correctly includes subgroup repos — not just top-level group repos)
        List<Repository> repos = repositoryRepository.findAllByWorkspaceMonitors(session.scopeId());

        if (repos.isEmpty()) {
            log.debug("No repositories to sync for GitLab workspace: scopeId={}", session.scopeId());
            return List.of();
        }

        // Use current monitor identities after reconciliation so each phase can write
        // its per-repo watermark via the SPI without reaching into workspace internals.
        Map<String, Long> syncTargetIdsByNameWithOwner =
                syncTargetProvider.getSyncTargetsForScope(session.scopeId()).stream()
                        .collect(Collectors.toMap(SyncTarget::repositoryNameWithOwner, SyncTarget::id, (a, b) -> a));

        List<Repository> availableRepos = new ArrayList<>();
        Map<Long, Long> availableTargetIds = new HashMap<>();
        // Each completed repository's watermark write, held until the link pass that reads the watermark has finished.
        Map<Long, Runnable> heldWatermarks = new LinkedHashMap<>();
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
            if (cancellation.stopsHere()) {
                log.info(
                        "GitLab scheduled sync cancelled between repositories: scopeId={}, reposProcessed={}, reposRemaining={}",
                        session.scopeId(),
                        reposProcessed,
                        totalRepos - reposProcessed);
                break;
            }
            // A credential refused during this run ends it: every remaining repository would repeat the refusal.
            if (!syncTargetProvider.isScopeActiveForSync(session.scopeId())) {
                log.info(
                        "GitLab sync stopped between repositories: reason=scopeNotEligible, scopeId={}, reposProcessed={},"
                                + " reposRemaining={}",
                        session.scopeId(),
                        reposProcessed,
                        totalRepos - reposProcessed);
                break;
            }

            // Look up the sync-target id from the session so each phase can write its
            // per-repo watermark via the SPI. Not all repositories have an entry in the
            // refreshed targets (edge case); without a monitor, do not fetch the row.
            Long rtmId = syncTargetIdsByNameWithOwner.get(repo.getNameWithOwner());

            if (rtmId == null) continue;
            var read = admission
                    .capture(session.scopeId(), repo)
                    .filter(captured -> captured.monitorId() == rtmId)
                    .orElse(null);
            if (read == null) continue;
            reads.put(repo.getId(), read);
            var deferred = admission.write(
                    read, () -> syncTargetProvider.deferUnavailableRepository(session.scopeId(), rtmId));
            if (deferred.isEmpty()) continue;
            if (deferred.get()) {
                GitLabSyncReports.warn(
                        handle,
                        SyncResult.Status.ABORTED_ERROR,
                        SyncPhase.REPOSITORIES,
                        "Repository sync",
                        repo.getNameWithOwner());
                continue;
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
                    if (!admission.current(read)) break;
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_RATE_LIMIT,
                            SyncPhase.REPOSITORIES,
                            "Repository sync",
                            repo.getNameWithOwner());
                    break;
                }
            }

            // Capture updatedAfter ONCE per repo before any sync phase
            OffsetDateTime updatedAfter = null;
            if (repo.getLastSyncAt() != null) {
                Instant buffered = repo.getLastSyncAt().minus(Duration.ofMinutes(5));
                updatedAfter = buffered.atOffset(ZoneOffset.UTC);
            }

            Instant metadataRequestedAt = Instant.now();
            try {
                var metadata = projectSyncService.fetchProjectById(session.scopeId(), repo.getNativeId());
                if (!admission.current(read)) continue;
                if (metadata.isEmpty()) {
                    syncTargetProvider.recordRepositoryUnavailable(session.scopeId(), rtmId);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Repository metadata sync",
                            repo.getNameWithOwner());
                    continue;
                }
                Repository beforeRefresh = repo;
                var refreshed = admission.write(
                        read,
                        () -> projectSyncService
                                .persistProject(metadata.get(), beforeRefresh.getProvider())
                                .orElse(null));
                if (!admission.current(read)) continue;
                if (refreshed.isEmpty()) {
                    syncTargetProvider.retryUnavailableRepository(session.scopeId(), rtmId);
                    resourceErrors.put(rtmId, "Repository metadata sync failed");
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Repository metadata sync",
                            repo.getNameWithOwner());
                    continue;
                }
                repo = refreshed.get();
                syncTargetProvider.reconcileSyncTargetIdentity(rtmId, repo.getNativeId(), repo.getNameWithOwner());
                syncTargetProvider.clearRepositoryUnavailable(session.scopeId(), rtmId);
                admission.runWrite(read, () -> {
                    syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.REPOSITORY_VISIBILITY, metadataRequestedAt);
                    // A project's visibility does not cover its repository, issues and merge requests, and Hephaestus
                    // does not list GitLab members yet, so no GitLab proof waits for a sync that cannot end it.
                    syncTargetProvider.recordReadersNotListable(rtmId);
                });
                availableRepos.add(repo);
                availableTargetIds.put(repo.getId(), rtmId);
            } catch (RepositoryNotFoundOnGitProviderException | WebClientResponseException.NotFound e) {
                if (!admission.current(read)) continue;
                syncTargetProvider.recordRepositoryUnavailable(session.scopeId(), rtmId);
                GitLabSyncReports.warn(
                        handle,
                        SyncResult.Status.ABORTED_ERROR,
                        SyncPhase.REPOSITORIES,
                        "Repository metadata sync",
                        repo.getNameWithOwner());
                continue;
            } catch (Exception e) {
                if (!admission.current(read)) continue;
                // A refused credential, rate limit, partial response or transport failure is not absence.
                syncTargetProvider.retryUnavailableRepository(session.scopeId(), rtmId);
                resourceErrors.put(rtmId, "Repository metadata sync failed");
                GitLabSyncReports.warn(
                        handle,
                        SyncResult.Status.ABORTED_ERROR,
                        SyncPhase.REPOSITORIES,
                        "Repository metadata sync",
                        repo.getNameWithOwner());
                continue;
            }

            String error = null;
            boolean issuesDone = false;
            boolean mrsDone = false;

            // Labels
            if (labelSync != null) {
                try {
                    SyncResult r = labelSync.syncLabelsForRepository(session.scopeId(), repo, read);
                    if (!admission.current(read)) continue;
                    totalLabels += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (error == null) {
                            error = "Label sync: " + r.status();
                        }
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.REPOSITORIES, "Label sync", repo.getNameWithOwner());
                    }
                    if (rtmId != null && r.isCompleted()) {
                        admission.runWrite(
                                read,
                                () -> syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.LABELS, Instant.now()));
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Label sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn("Failed label sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Label sync",
                            repo.getNameWithOwner());
                }
            }

            // Milestones
            if (milestoneSync != null) {
                try {
                    SyncResult r = milestoneSync.syncMilestonesForRepository(session.scopeId(), repo, read);
                    if (!admission.current(read)) continue;
                    totalMilestones += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (error == null) {
                            error = "Milestone sync: " + r.status();
                        }
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.ISSUES, "Milestone sync", repo.getNameWithOwner());
                    }
                    if (rtmId != null && r.isCompleted()) {
                        admission.runWrite(
                                read,
                                () -> syncTargetProvider.updateSyncTimestamp(
                                        rtmId, SyncType.MILESTONES, Instant.now()));
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Milestone sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn(
                            "Failed milestone sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.ISSUES,
                            "Milestone sync",
                            repo.getNameWithOwner());
                }
            }

            // Each kind resumes its own open window. The repository's watermark moves only when both are read, and
            // never past the start of a window (RecentWindows).
            SyncTarget recent = syncTargetProvider.findSyncTargetById(rtmId).orElse(null);
            RecentWindow issueWindow = recent == null ? null : recent.issueRecentWindow();
            RecentWindow mrWindow = recent == null ? null : recent.pullRequestRecentWindow();
            OffsetDateTime issuesAfter = GitLabWatermarks.updatedAfter(
                    recent == null ? null : recent.lastIssuesSyncedAt(), repo.getLastSyncAt());
            OffsetDateTime mrsAfter = GitLabWatermarks.updatedAfter(
                    recent == null ? null : recent.lastPullRequestsSyncedAt(), repo.getLastSyncAt());
            Instant passStart = Instant.now();
            Instant issueMark = null;
            Instant mrMark = null;

            // Issues
            if (issueSync != null) {
                try {
                    RecentSyncResult issuePass = issueSync.syncIssues(
                            session.scopeId(), repo, issuesAfter, RecentWindows.resumeCursor(issueWindow), read);
                    SyncResult r = issuePass.result();
                    if (!admission.current(read)) continue;
                    totalIssues += r.count();
                    if (error == null && r.status() != SyncResult.Status.COMPLETED) {
                        error = RecentWindows.describe("Issue sync", issuePass);
                    }
                    issueMark = admission
                            .write(
                                    read,
                                    () -> RecentWindows.record(
                                            syncTargetProvider,
                                            rtmId,
                                            SyncCursorKind.ISSUE,
                                            issueWindow,
                                            passStart,
                                            issuePass))
                            .orElse(null);
                    issuesDone = issueMark != null;
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.ISSUES, "Issue sync", repo.getNameWithOwner());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Issue sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn("Failed issue sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.ISSUES,
                            "Issue sync",
                            repo.getNameWithOwner());
                }
            }

            // Merge Requests
            if (mrSync != null) {
                try {
                    RecentSyncResult mrPass = mrSync.syncMergeRequests(
                            session.scopeId(), repo, mrsAfter, RecentWindows.resumeCursor(mrWindow), read);
                    SyncResult r = mrPass.result();
                    if (!admission.current(read)) continue;
                    totalMRs += r.count();
                    if (error == null && r.status() != SyncResult.Status.COMPLETED) {
                        error = RecentWindows.describe("Merge request sync", mrPass);
                    }
                    mrMark = admission
                            .write(
                                    read,
                                    () -> RecentWindows.record(
                                            syncTargetProvider,
                                            rtmId,
                                            SyncCursorKind.PULL_REQUEST,
                                            mrWindow,
                                            passStart,
                                            mrPass))
                            .orElse(null);
                    mrsDone = mrMark != null;
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        GitLabSyncReports.warn(
                                handle,
                                r.status(),
                                SyncPhase.PULL_REQUESTS,
                                "Merge request sync",
                                repo.getNameWithOwner());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Merge request sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn("Failed MR sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.PULL_REQUESTS,
                            "Merge request sync",
                            repo.getNameWithOwner());
                }
            }

            // Collaborators
            if (collaboratorSync != null) {
                try {
                    SyncResult r = collaboratorSync.syncCollaboratorsForRepository(session.scopeId(), repo, read);
                    if (!admission.current(read)) continue;
                    totalCollaborators += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (error == null) {
                            error = "Collaborator sync: " + r.status();
                        }
                        GitLabSyncReports.warn(
                                handle,
                                r.status(),
                                SyncPhase.REPOSITORIES,
                                "Collaborator sync",
                                repo.getNameWithOwner());
                    }
                    if (rtmId != null && r.isCompleted()) {
                        admission.runWrite(
                                read,
                                () -> syncTargetProvider.updateSyncTimestamp(
                                        rtmId, SyncType.COLLABORATORS, Instant.now()));
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Collaborator sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn(
                            "Failed collaborator sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Collaborator sync",
                            repo.getNameWithOwner());
                }
            }

            // Commits — the JGit backfill provides diff stats and file changes; the REST sync
            // runs only when no backfill bean exists.
            if (commitBackfill != null) {
                try {
                    SyncResult r = commitBackfill.backfillCommits(session.scopeId(), repo, read);
                    if (!admission.current(read)) continue;
                    totalCommits += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (error == null) {
                            error = "Commit sync: " + r.status();
                        }
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.REPOSITORIES, "Commit backfill", repo.getNameWithOwner());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Commit backfill sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn(
                            "Failed commit backfill: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Commit backfill",
                            repo.getNameWithOwner());
                }
            } else if (commitSync != null) {
                try {
                    SyncResult r = commitSync.syncCommitsForRepository(session.scopeId(), repo, updatedAfter, read);
                    if (!admission.current(read)) continue;
                    totalCommits += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (error == null) {
                            error = "Commit sync: " + r.status();
                        }
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.REPOSITORIES, "Commit sync", repo.getNameWithOwner());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (error == null) {
                        error = "Commit sync failed (" + e.getClass().getSimpleName() + ")";
                    }
                    log.warn("Failed commit sync: scopeId={}, repo={}", session.scopeId(), repo.getNameWithOwner(), e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Commit sync",
                            repo.getNameWithOwner());
                }
            }

            boolean attempted = labelSync != null
                    || milestoneSync != null
                    || issueSync != null
                    || mrSync != null
                    || collaboratorSync != null
                    || commitBackfill != null
                    || commitSync != null;
            // A repository with no active sync service has not passed a health check.
            boolean allDone =
                    attempted && error == null && (issueSync == null || issuesDone) && (mrSync == null || mrsDone);
            if (allDone) {
                Instant completedAt = GitLabWatermarks.earliest(passStart, issueMark, mrMark);
                heldWatermarks.put(
                        repo.getId(),
                        () -> admission.runWrite(read, () -> {
                            repositoryRepository.updateLastSyncAt(read.repositoryId(), completedAt);
                            syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.FULL_REPOSITORY, completedAt);
                        }));
            }
            if (rtmId != null && attempted) {
                resourceErrors.put(rtmId, error);
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
        Set<Long> unlinked = new HashSet<>();
        boolean linkPassDone = commitMrLinker == null;
        if (commitMrLinker != null
                && !cancellation.stopsHere()
                && syncTargetProvider.isScopeActiveForSync(session.scopeId())) {
            linkPassDone = true;
            for (Repository repo : availableRepos) {
                var read = reads.get(repo.getId());
                if (read == null || !admission.current(read)) continue;
                // An INITIAL sync links every merge request. A commit stored after its merge request was last read
                // gets its link only from this full sweep. Other runs read merge requests updated since the watermark.
                OffsetDateTime repoUpdatedAfter = null;
                if (type != SyncJobType.INITIAL && repo.getLastSyncAt() != null) {
                    Instant buffered = repo.getLastSyncAt().minus(Duration.ofMinutes(5));
                    repoUpdatedAfter = buffered.atOffset(ZoneOffset.UTC);
                }
                try {
                    SyncResult result = commitMrLinker.linkCommits(session.scopeId(), repo, repoUpdatedAfter, read);
                    if (!admission.current(read)) continue;
                    if (result.status() != SyncResult.Status.COMPLETED) {
                        unlinked.add(repo.getId());
                        GitLabSyncReports.warn(
                                handle,
                                result.status(),
                                SyncPhase.REPOSITORIES,
                                "Commit linking",
                                repo.getNameWithOwner());
                    }
                    Long targetId = availableTargetIds.get(repo.getId());
                    if (targetId != null) {
                        resourceErrors.putIfAbsent(
                                targetId,
                                result.status() == SyncResult.Status.COMPLETED
                                        ? null
                                        : "Commit linking: " + result.status());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    unlinked.add(repo.getId());
                    Long targetId = availableTargetIds.get(repo.getId());
                    if (targetId != null) {
                        resourceErrors.putIfAbsent(
                                targetId,
                                "Commit linking failed (" + e.getClass().getSimpleName() + ")");
                    }
                    log.warn(
                            "Failed commit→MR linking (second pass): scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.REPOSITORIES,
                            "Commit linking",
                            repo.getNameWithOwner());
                }
            }
        }

        // A skipped or failed link pass holds the watermark, so the next run reads the same merge requests again.
        if (linkPassDone) {
            heldWatermarks.forEach((repositoryId, write) -> {
                if (!unlinked.contains(repositoryId)) write.run();
            });
        }

        for (Repository repo : availableRepos) {
            var read = reads.get(repo.getId());
            if (read == null || !admission.current(read)) continue;
            try {
                admission.runWrite(
                        read, () -> activityLedgerRepair.reconcileRepository(session.scopeId(), repo.getId()));
            } catch (RuntimeException e) {
                if (!admission.current(read)) continue;
                log.warn(
                        "Activity ledger repair failed: workspaceId={}, repositoryId={}",
                        session.scopeId(),
                        repo.getId(),
                        e);
                GitLabSyncReports.warn(
                        handle,
                        SyncResult.Status.ABORTED_ERROR,
                        SyncPhase.REPOSITORIES,
                        "Activity repair",
                        repo.getNameWithOwner());
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
        return availableRepos;
    }

    void syncPostRepo(
            SyncSession session,
            @Nullable SyncExecutionHandle handle,
            Map<Long, String> resourceErrors,
            List<Repository> repos,
            Map<Long, GitLabProjectAdmission.ProjectRead> reads) {
        if (subIssueSync == null && issueDependencySync == null) return;

        int totalSubIssues = 0, totalDeps = 0;

        Map<String, Long> syncTargetIdsByName = syncTargetProvider.getSyncTargetsForScope(session.scopeId()).stream()
                .collect(Collectors.toMap(SyncTarget::repositoryNameWithOwner, SyncTarget::id, (a, b) -> a));

        for (Repository repo : repos) {
            var read = reads.get(repo.getId());
            if (read == null || !admission.current(read)) continue;
            Long syncTargetId = syncTargetIdsByName.get(repo.getNameWithOwner());
            if (syncTargetId != null && syncTargetProvider.isRepositoryUnavailable(session.scopeId(), syncTargetId)) {
                continue;
            }
            if (syncTargetId != null) {
                resourceErrors.putIfAbsent(syncTargetId, null);
            }
            if (subIssueSync != null) {
                try {
                    SyncResult r = subIssueSync.syncSubIssuesForRepository(session.scopeId(), repo, read);
                    if (!admission.current(read)) continue;
                    totalSubIssues += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (syncTargetId != null) {
                            resourceErrors.putIfAbsent(syncTargetId, "Sub-issue sync: " + r.status());
                        }
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.ISSUES, "Sub-issue sync", repo.getNameWithOwner());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (syncTargetId != null) {
                        resourceErrors.putIfAbsent(
                                syncTargetId,
                                "Sub-issue sync failed (" + e.getClass().getSimpleName() + ")");
                    }
                    log.warn(
                            "Failed sub-issue sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.ISSUES,
                            "Sub-issue sync",
                            repo.getNameWithOwner());
                }
            }
            if (issueDependencySync != null) {
                try {
                    SyncResult r = issueDependencySync.syncDependenciesForRepository(session.scopeId(), repo, read);
                    if (!admission.current(read)) continue;
                    totalDeps += r.count();
                    if (r.status() != SyncResult.Status.COMPLETED) {
                        if (syncTargetId != null) {
                            resourceErrors.putIfAbsent(syncTargetId, "Dependency sync: " + r.status());
                        }
                        GitLabSyncReports.warn(
                                handle, r.status(), SyncPhase.ISSUES, "Issue dependency sync", repo.getNameWithOwner());
                    }
                } catch (Exception e) {
                    if (!admission.current(read)) continue;
                    if (syncTargetId != null) {
                        resourceErrors.putIfAbsent(
                                syncTargetId,
                                "Dependency sync failed (" + e.getClass().getSimpleName() + ")");
                    }
                    log.warn(
                            "Failed dependency sync: scopeId={}, repo={}",
                            session.scopeId(),
                            repo.getNameWithOwner(),
                            e);
                    GitLabSyncReports.warn(
                            handle,
                            SyncResult.Status.ABORTED_ERROR,
                            SyncPhase.ISSUES,
                            "Issue dependency sync",
                            repo.getNameWithOwner());
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

    /** The instance the scope's active connection is on; the same group path can exist on another one. */
    private @Nullable Long getGitLabProviderId(Long scopeId) {
        return actorSelector.connectedProviderId(scopeId).orElse(null);
    }
}
