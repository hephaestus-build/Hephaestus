package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Initializes a GitLab PAT workspace by discovering all repositories in its group.
 *
 * <p>Encapsulates the GitLab-specific initialization sequence that runs both at
 * workspace creation time (async, fire-and-forget) and during startup activation.
 * The sequence:
 * <ol>
 *   <li>Token rotation (if expiring soon)</li>
 *   <li>Webhook registration (idempotent)</li>
 *   <li>Group project sync via GraphQL (discovers all projects in group + subgroups)</li>
 *   <li>Organization linking (workspace → synced Organization entity)</li>
 *   <li>Repository monitor creation (RepositoryToMonitor entries for each project)</li>
 * </ol>
 *
 * <p>This service is intentionally in the {@code workspace} package because it bridges
 * workspace lifecycle (creation/activation) with integration.scm sync services. The dependency
 * direction is always {@code workspace → integration.scm}, never the reverse.
 *
 * @see WorkspaceActivationService
 * @see de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService
 */
@Service
public class GitLabWorkspaceInitializationService {

    private static final Logger log = LoggerFactory.getLogger(GitLabWorkspaceInitializationService.class);

    // Core repositories
    private final WorkspaceRepository workspaceRepository;
    private final OrganizationRepository organizationRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;

    // Configuration
    private final NatsConnectionProperties natsProperties;
    private final SyncSchedulerProperties syncSchedulerProperties;

    // Services — natsConsumerService absent under webhook profile.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;
    private final SyncTargetProvider syncTargetProvider;

    // Lazy-loaded: optional GitLab beans gated by @ConditionalOnProperty
    private final ObjectProvider<GitLabSyncServiceHolder> gitLabSyncServiceHolderProvider;
    private final ObjectProvider<GitLabWebhookService> gitLabWebhookServiceProvider;
    private final ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider;
    private final ObjectProvider<GitLabWorkspaceDataSyncTrigger> dataSyncTriggerProvider;

    // Authoritative source for per-workspace integration config (server URL, PAT presence).
    private final ConnectionService connectionService;
    private final GitLabWorkspaceLinkService workspaceLinkService;

    // Infrastructure
    private final AsyncTaskExecutor monitoringExecutor;

    public GitLabWorkspaceInitializationService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            NatsConnectionProperties natsProperties,
            SyncSchedulerProperties syncSchedulerProperties,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            SyncTargetProvider syncTargetProvider,
            ObjectProvider<GitLabSyncServiceHolder> gitLabSyncServiceHolderProvider,
            ObjectProvider<GitLabWebhookService> gitLabWebhookServiceProvider,
            ObjectProvider<GitLabRateLimitTracker> rateLimitTrackerProvider,
            ObjectProvider<GitLabWorkspaceDataSyncTrigger> dataSyncTriggerProvider,
            ConnectionService connectionService,
            GitLabWorkspaceLinkService workspaceLinkService,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.natsProperties = natsProperties;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.natsConsumerService = natsConsumerService;
        this.syncTargetProvider = syncTargetProvider;
        this.gitLabSyncServiceHolderProvider = gitLabSyncServiceHolderProvider;
        this.gitLabWebhookServiceProvider = gitLabWebhookServiceProvider;
        this.rateLimitTrackerProvider = rateLimitTrackerProvider;
        this.dataSyncTriggerProvider = dataSyncTriggerProvider;
        this.connectionService = connectionService;
        this.workspaceLinkService = workspaceLinkService;
        this.monitoringExecutor = monitoringExecutor;
    }

    /**
     * Triggers GitLab workspace initialization asynchronously. Returns immediately so the
     * caller's HTTP response is not blocked; discovery + NATS consumer start run on the
     * monitoring executor.
     */
    public void initializeAsync(Long workspaceId) {
        monitoringExecutor.submit(() -> {
            try {
                Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
                if (workspace == null) {
                    log.warn(
                            "Skipped async GitLab initialization: reason=workspaceNotFound, workspaceId={}",
                            workspaceId);
                    return;
                }

                // Set workspace context for entire lifecycle (init + sync + NATS).
                // initialize() checks contextOwner and won't double-set/clear.
                // GitLab workspaces never have a GitHub App installation id.
                WorkspaceContext context =
                        WorkspaceContext.fromWorkspace(workspace, Set.of(), /* installationId */ null);
                WorkspaceContextHolder.setContext(context);
                try {
                    dataSyncTriggerProvider.getObject().syncAllRepositories(workspaceId);
                    startNatsConsumer(workspace);
                } finally {
                    WorkspaceContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.error("Failed async GitLab workspace initialization: workspaceId={}", workspaceId, e);
            }
        });
    }

    /**
     * Runs the core GitLab initialization sequence for a workspace:
     * webhook setup, project discovery, organization linking, and monitor creation.
     *
     * <p>Called both from {@link #initializeAsync(Long)} (creation time) and from
     * {@link WorkspaceActivationService#activateWorkspace} (startup time).
     *
     * <p>Each phase is isolated: a failure in webhook registration does not prevent
     * project discovery.
     *
     * <p><b>Note:</b> This method does NOT start the NATS consumer. Callers are responsible
     * for starting NATS at the appropriate point in their lifecycle. At creation time,
     * {@link #initializeAsync} starts it after initialization. At startup time,
     * {@link WorkspaceActivationService} starts it after full sync completes.
     *
     * @param workspace the GITLAB_PAT workspace to initialize
     */
    public void initialize(Workspace workspace) {
        var gitLabConfigOpt = connectionService.findActiveGitLabConfig(workspace.getId());
        if (gitLabConfigOpt.isEmpty()) {
            return;
        }

        boolean hasToken = connectionService
                .findActiveBearerToken(workspace.getId(), IntegrationKind.GITLAB)
                .map(b -> b.token() != null && !b.token().isBlank())
                .orElse(false);
        if (!hasToken) {
            log.warn("Skipped GitLab initialization: reason=missingToken, workspaceId={}", workspace.getId());
            return;
        }

        String accountLogin = workspace.getAccountLogin();
        if (accountLogin == null || accountLogin.isBlank()) {
            log.warn("Skipped GitLab initialization: reason=missingAccountLogin, workspaceId={}", workspace.getId());
            return;
        }

        // Only set context if not already set (avoids clearing caller's context).
        // GitLab workspaces never have a GitHub App installation id.
        boolean contextOwner = WorkspaceContextHolder.getContext() == null;
        if (contextOwner) {
            WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, Set.of(), /* installationId */ null);
            WorkspaceContextHolder.setContext(context);
        }
        try {
            log.info(
                    "Starting GitLab workspace initialization: workspaceId={}, accountLogin={}",
                    workspace.getId(),
                    LoggingUtils.sanitizeForLog(workspace.getAccountLogin()));

            // Phase 0: Token rotation + webhook registration
            setupWebhook(workspace);

            // Phase 1: Discover group projects via GraphQL
            List<Repository> syncedRepos = discoverGroupProjects(workspace);

            // Phase 2: Link organization + create monitors
            if (!syncedRepos.isEmpty()) {
                linkWorkspaceToOrganization(workspace);
                int created = ensureRepositoryMonitors(workspace, syncedRepos);
                // Update NATS consumer subscriptions to include newly discovered repos
                if (created > 0 && natsProperties.enabled()) {
                    natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspace.getId()));
                }
            }

            log.info(
                    "Completed GitLab workspace initialization: workspaceId={}, repos={}",
                    workspace.getId(),
                    syncedRepos.size());
        } finally {
            if (contextOwner) {
                WorkspaceContextHolder.clearContext();
            }
        }
    }

    /**
     * Runs token rotation and webhook registration.
     * Errors are logged but do not abort initialization.
     */
    private void setupWebhook(Workspace workspace) {
        var webhookService = gitLabWebhookServiceProvider.getIfAvailable();
        if (webhookService == null) {
            return;
        }

        try {
            webhookService.rotateTokenIfNeeded(workspace);
        } catch (Exception e) {
            log.warn("Token rotation failed (non-fatal): workspaceId={}", workspace.getId(), e);
        }

        try {
            var webhookResult = webhookService.registerWebhook(workspace);
            if (webhookResult.registered()) {
                log.info(
                        "Webhook setup: workspaceId={}, registered=true, webhookId={}",
                        workspace.getId(),
                        webhookResult.webhookId());
            } else {
                log.warn(
                        "Webhook setup: workspaceId={}, registered=false, reason={}",
                        workspace.getId(),
                        LoggingUtils.sanitizeForLog(webhookResult.failureReason()));
            }
        } catch (Exception e) {
            log.warn("Webhook registration failed (non-fatal): workspaceId={}", workspace.getId(), e);
        }
    }

    /**
     * Discovers all projects in the workspace's GitLab group via GraphQL.
     *
     * @return list of synced repositories (empty on failure, never null)
     */
    private List<Repository> discoverGroupProjects(Workspace workspace) {
        var gitLabServices = gitLabSyncServiceHolderProvider.getIfAvailable();
        var syncService = gitLabServices != null ? gitLabServices.getGroupSyncService() : null;

        if (syncService == null) {
            log.warn(
                    "Skipped GitLab project discovery: reason=syncServiceUnavailable, workspaceId={}",
                    workspace.getId());
            return Collections.emptyList();
        }

        try {
            // Pass the connection's server URL so per-instance repos get stamped with the matching
            // git_provider row instead of falling back to the global default (which silently fuses
            // cross-instance identities under gitlab.com).
            String serverUrl = connectionService
                    .findActiveGitLabConfig(workspace.getId())
                    .map(ConnectionConfig.GitLabConfig::serverUrl)
                    .orElse(null);
            GitLabSyncResult result =
                    syncService.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), serverUrl);
            log.info(
                    "GitLab project discovery: workspaceId={}, status={}, synced={}, failed={}, pages={}",
                    workspace.getId(),
                    result.status(),
                    result.synced().size(),
                    result.projectsSkipped(),
                    result.pagesCompleted());
            return result.synced();
        } catch (Exception e) {
            log.error("Failed GitLab project discovery: workspaceId={}", workspace.getId(), e);
            return Collections.emptyList();
        }
    }

    public void linkWorkspaceToOrganization(Workspace workspace) {
        workspaceLinkService.link(workspace);
    }

    /**
     * Creates {@link RepositoryToMonitor} entries for each synced repository.
     * Existing monitors are not duplicated.
     *
     * @return number of newly created monitors
     */
    public int ensureRepositoryMonitors(Workspace workspace, List<Repository> syncedRepos) {
        Set<String> existing = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()).stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .collect(Collectors.toSet());

        int created = 0;
        for (Repository repo : syncedRepos) {
            String nwo = repo.getNameWithOwner();
            if (nwo == null || existing.contains(nwo)) {
                continue;
            }
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setNameWithOwner(nwo);
            monitor.setWorkspace(workspace);
            repositoryToMonitorRepository.save(monitor);
            created++;
        }
        if (created > 0) {
            log.info(
                    "Created repository monitors: workspaceId={}, created={}, total={}",
                    workspace.getId(),
                    created,
                    syncedRepos.size());
        }
        return created;
    }

    // Full data sync: memberships, issue types, per-repo data, teams

    /**
     * Runs the full GitLab data sync for a workspace: memberships, issue types,
     * per-repo data (labels, milestones, issues, MRs, collaborators, commits),
     * sub-issues, dependencies, and teams.
     *
     * <p>Called both at creation time (from {@link #initializeAsync}) and at
     * startup (from {@link WorkspaceActivationService#activateWorkspace}).
     */
    public void syncFullData(Workspace workspace) {
        syncFullData(workspace, () -> false);
    }

    /**
     * Same as {@link #syncFullData(Workspace)}, but cooperatively cancellable — used by
     * {@code GitlabIntegrationSyncRunner} so a {@code SyncJob} cancel request can stop the pass
     * between repositories rather than running to completion. {@code cancelled} is polled at the
     * top of the per-repository loop in {@link #syncGitLabRepositories}, which dominates the
     * runtime of a full sync; the membership/issue-type/teams phases that bookend the loop are
     * comparatively cheap and are not individually cancellable.
     */
    public void syncFullData(Workspace workspace, BooleanSupplier cancelled) {
        var gitLabServices = gitLabSyncServiceHolderProvider.getIfAvailable();
        if (gitLabServices == null) {
            log.debug(
                    "Skipped GitLab full data sync: reason=syncServicesUnavailable, workspaceId={}", workspace.getId());
            return;
        }
        String accountLogin = workspace.getAccountLogin();
        if (accountLogin == null || accountLogin.isBlank()) {
            return;
        }

        var memberSyncService = gitLabServices.getGroupMemberSyncService();
        if (memberSyncService != null) {
            organizationRepository
                    .findByLoginIgnoreCaseAndProvider_Type(accountLogin, IdentityProviderType.GITLAB)
                    .ifPresent(org -> {
                        try {
                            int membersSynced =
                                    memberSyncService.syncGroupMemberships(workspace.getId(), accountLogin, org);
                            log.info(
                                    "GitLab membership sync: workspaceId={}, membersSynced={}",
                                    workspace.getId(),
                                    membersSynced);
                        } catch (Exception e) {
                            log.warn("Failed membership sync: workspaceId={}", workspace.getId(), e);
                        }
                    });
        }

        // Sync issue types (org-level, lightweight)
        var issueTypeSyncService = gitLabServices.getIssueTypeSyncService();
        if (issueTypeSyncService != null) {
            try {
                int issueTypes = issueTypeSyncService.syncIssueTypesForGroup(workspace.getId(), accountLogin);
                log.info("GitLab issue type sync: workspaceId={}, types={}", workspace.getId(), issueTypes);
            } catch (Exception e) {
                log.warn("Failed issue type sync: workspaceId={}", workspace.getId(), e);
            }
        }

        // Resolve repos via workspace monitors (includes subgroup repos)
        List<Repository> repos = repositoryRepository.findAllByWorkspaceMonitors(workspace.getId());

        if (repos.isEmpty()) {
            log.debug("Skipped GitLab repo sync: reason=noRepos, workspaceId={}", workspace.getId());
        } else {
            // Phase 2.6: group milestone fan-out (mirrors scheduler). Must run before the per-repo
            // issue sync so milestone references on issues can be resolved by iid. Without this the
            // milestone table stays empty on a fresh sync because project.milestones(includeAncestors)
            // does not reliably surface group milestones in every GitLab deployment.
            var milestoneSyncService = gitLabServices.getMilestoneSyncService();
            if (milestoneSyncService != null) {
                try {
                    SyncResult groupMilestoneResult =
                            milestoneSyncService.syncMilestonesForGroup(workspace.getId(), accountLogin, repos);
                    log.info(
                            "GitLab group milestone sync: workspaceId={}, group={}, written={}",
                            workspace.getId(),
                            LoggingUtils.sanitizeForLog(accountLogin),
                            groupMilestoneResult.count());
                } catch (Exception e) {
                    log.warn(
                            "Failed GitLab group milestone sync: workspaceId={}, group={}",
                            workspace.getId(),
                            LoggingUtils.sanitizeForLog(accountLogin),
                            e);
                }
            }

            // Build a snapshot of commit→MR linker work to perform in a second pass, so a
            // commit whose SHA appears on an MR in a sibling repo can still be linked after
            // all repos have finished syncing.
            List<Repository> commitLinkTargets = new ArrayList<>();
            syncGitLabRepositories(workspace, gitLabServices, repos, commitLinkTargets, cancelled);

            // Second pass: link commits to MRs now that every repo has populated its commits
            // and every MR-targeted repo has populated its MRs.
            var commitMrLinker = gitLabServices.getCommitMergeRequestLinker();
            if (commitMrLinker != null) {
                for (Repository repo : commitLinkTargets) {
                    try {
                        commitMrLinker.linkCommits(workspace.getId(), repo, null);
                    } catch (Exception e) {
                        log.warn(
                                "Failed commit→MR linking (second pass): workspaceId={}, repo={}",
                                workspace.getId(),
                                repo.getNameWithOwner(),
                                e);
                    }
                }
            }

            syncGitLabPostRepo(workspace, gitLabServices, repos);
        }

        // Teams (subgroups)
        var teamSyncService = gitLabServices.getTeamSyncService();
        if (teamSyncService != null) {
            try {
                int teamsCount = teamSyncService.syncTeamsForGroup(workspace.getId(), accountLogin);
                // Stamp the teams watermark so the cron scheduler's cooldown logic reflects that
                // initial sync just ran; otherwise cron would re-sync teams redundantly right after
                // workspace activation.
                syncTargetProvider.updateTeamsSyncTimestamp(workspace.getId(), Instant.now());
                log.info("GitLab team sync complete: workspaceId={}, teams={}", workspace.getId(), teamsCount);
            } catch (Exception e) {
                log.warn("Failed to sync teams: workspaceId={}", workspace.getId(), e);
            }
        }
    }

    /**
     * Syncs per-repository data (labels, milestones, issues, MRs, collaborators, commits).
     *
     * <p>Integrates rate limit awareness matching the cron scheduler pattern:
     * checks {@link GitLabRateLimitTracker#isCritical} before each repository and
     * blocks via {@link GitLabRateLimitTracker#waitIfNeeded} when the remaining
     * budget drops below the critical threshold.
     *
     * <p>Applies cooldown for slowly-changing entities (labels, milestones, collaborators):
     * if a repository was synced within {@code syncSchedulerProperties.cooldownMinutes()},
     * these entities are skipped. Issues and MRs always sync incrementally via
     * {@code updatedAfter}.
     *
     * <p>{@code cancelled} is polled at the top of every iteration — cooperative cancellation,
     * best-effort: a repository already in progress always finishes its own phases.
     */
    private void syncGitLabRepositories(
            Workspace workspace,
            GitLabSyncServiceHolder gitLabServices,
            List<Repository> repos,
            List<Repository> commitLinkTargets,
            BooleanSupplier cancelled) {
        var labelSyncService = gitLabServices.getLabelSyncService();
        var milestoneSyncService = gitLabServices.getMilestoneSyncService();
        var issueSyncService = gitLabServices.getIssueSyncService();
        var mrSyncService = gitLabServices.getMergeRequestSyncService();
        var collaboratorSyncService = gitLabServices.getCollaboratorSyncService();
        var commitSyncService = gitLabServices.getCommitSyncService();
        var commitBackfillService = gitLabServices.getCommitBackfillService();

        // Map nameWithOwner → sync target id so each phase can stamp its per-repo watermark
        // via the SPI. Mirrors GitlabDataSyncScheduler.syncRepositories — without this the
        // initial sync left every watermark column NULL until the first cron run.
        Map<String, Long> syncTargetIdsByNameWithOwner =
                repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()).stream()
                        .collect(Collectors.toMap(
                                RepositoryToMonitor::getNameWithOwner, RepositoryToMonitor::getId, (a, b) -> a));

        GitLabRateLimitTracker rateLimitTracker = rateLimitTrackerProvider.getIfAvailable();
        Instant cooldownThreshold = Instant.now().minusSeconds(syncSchedulerProperties.cooldownMinutes() * 60L);

        int totalLabels = 0, totalMilestones = 0, totalIssues = 0, totalMRs = 0;
        int totalCollaborators = 0, totalCommits = 0;
        int skippedCooldown = 0;

        for (Repository repo : repos) {
            if (cancelled.getAsBoolean()) {
                log.info(
                        "GitLab repo sync cancelled: workspaceId={}, reposDone={}, reposTotal={}",
                        workspace.getId(),
                        commitLinkTargets.size(),
                        repos.size());
                break;
            }

            // Rate limit gate: block if remaining budget is critical
            if (rateLimitTracker != null && rateLimitTracker.isCritical(workspace.getId())) {
                log.info(
                        "Rate limit critical, waiting: workspaceId={}, remaining={}",
                        workspace.getId(),
                        rateLimitTracker.getRemaining(workspace.getId()));
                try {
                    rateLimitTracker.waitIfNeeded(workspace.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Rate limit wait interrupted, stopping repo sync: workspaceId={}", workspace.getId());
                    break;
                }
            }

            OffsetDateTime updatedAfter = null;
            if (repo.getLastSyncAt() != null) {
                Instant buffered = repo.getLastSyncAt().minus(Duration.ofMinutes(5));
                updatedAfter = buffered.atOffset(ZoneOffset.UTC);
            }

            // Look up the sync-target id so each phase can stamp its per-repo watermark via the SPI.
            Long rtmId = syncTargetIdsByNameWithOwner.get(repo.getNameWithOwner());

            // Cooldown: skip labels/milestones/collaborators if recently synced
            boolean skipSlowChanging =
                    repo.getLastSyncAt() != null && repo.getLastSyncAt().isAfter(cooldownThreshold);
            if (skipSlowChanging) {
                skippedCooldown++;
            }

            boolean issuesDone = false;
            boolean mrsDone = false;

            if (!skipSlowChanging && labelSyncService != null) {
                try {
                    SyncResult r = labelSyncService.syncLabelsForRepository(workspace.getId(), repo);
                    totalLabels += r.count();
                    if (rtmId != null) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.LABELS, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed label sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }
            if (!skipSlowChanging && milestoneSyncService != null) {
                try {
                    SyncResult r = milestoneSyncService.syncMilestonesForRepository(workspace.getId(), repo);
                    totalMilestones += r.count();
                    if (rtmId != null) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.MILESTONES, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed milestone sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }
            if (issueSyncService != null) {
                try {
                    SyncResult r = issueSyncService.syncIssues(workspace.getId(), repo, updatedAfter);
                    totalIssues += r.count();
                    issuesDone = r.isCompleted();
                    if (rtmId != null && issuesDone) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.ISSUES, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed issue sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }
            if (mrSyncService != null) {
                try {
                    SyncResult r = mrSyncService.syncMergeRequests(workspace.getId(), repo, updatedAfter);
                    totalMRs += r.count();
                    mrsDone = r.isCompleted();
                    if (rtmId != null && mrsDone) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.PULL_REQUESTS, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn("Failed MR sync: workspaceId={}, repo={}", workspace.getId(), repo.getNameWithOwner(), e);
                }
            }
            if (!skipSlowChanging && collaboratorSyncService != null) {
                try {
                    SyncResult r = collaboratorSyncService.syncCollaboratorsForRepository(workspace.getId(), repo);
                    totalCollaborators += r.count();
                    if (rtmId != null) {
                        syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.COLLABORATORS, Instant.now());
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed collaborator sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }
            // Prefer native Git backfill (provides diff stats + file changes);
            // fall back to REST API when git is not enabled
            if (commitBackfillService != null) {
                try {
                    SyncResult r = commitBackfillService.backfillCommits(workspace.getId(), repo);
                    totalCommits += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed commit backfill: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            } else if (commitSyncService != null) {
                try {
                    SyncResult r = commitSyncService.syncCommitsForRepository(workspace.getId(), repo, updatedAfter);
                    totalCommits += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed commit sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }

            // Record this repo for the second-pass commit→MR linker, which lets commits whose SHAs
            // appear on MRs in sibling repos be linked once every repo has synced its MRs.
            commitLinkTargets.add(repo);

            boolean allDone = (issueSyncService == null || issuesDone) && (mrSyncService == null || mrsDone);
            if (allDone) {
                repositoryRepository.updateLastSyncAt(repo.getId(), Instant.now());
                if (rtmId != null) {
                    syncTargetProvider.updateSyncTimestamp(rtmId, SyncType.FULL_REPOSITORY, Instant.now());
                }
            }
        }

        log.info(
                "GitLab repo sync complete: workspaceId={}, repos={}, labels={}, milestones={}, issues={}, mrs={}, "
                        + "collaborators={}, commits={}, cooldownSkipped={}",
                workspace.getId(),
                repos.size(),
                totalLabels,
                totalMilestones,
                totalIssues,
                totalMRs,
                totalCollaborators,
                totalCommits,
                skippedCooldown);
    }

    /**
     * Post-repo sync: sub-issues and issue dependencies.
     */
    private void syncGitLabPostRepo(
            Workspace workspace, GitLabSyncServiceHolder gitLabServices, List<Repository> repos) {
        var subIssueSync = gitLabServices.getSubIssueSyncService();
        var depSync = gitLabServices.getIssueDependencySyncService();
        if (subIssueSync == null && depSync == null) return;

        int totalSubIssues = 0, totalDeps = 0;
        for (Repository repo : repos) {
            if (subIssueSync != null) {
                try {
                    SyncResult r = subIssueSync.syncSubIssuesForRepository(workspace.getId(), repo);
                    totalSubIssues += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed sub-issue sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }
            if (depSync != null) {
                try {
                    SyncResult r = depSync.syncDependenciesForRepository(workspace.getId(), repo);
                    totalDeps += r.count();
                } catch (Exception e) {
                    log.warn(
                            "Failed dependency sync: workspaceId={}, repo={}",
                            workspace.getId(),
                            repo.getNameWithOwner(),
                            e);
                }
            }
        }
        if (totalSubIssues > 0 || totalDeps > 0) {
            log.info(
                    "GitLab post-repo sync: workspaceId={}, subIssues={}, deps={}",
                    workspace.getId(),
                    totalSubIssues,
                    totalDeps);
        }
    }

    /**
     * Starts the NATS consumer for webhook event processing if NATS is enabled.
     */
    private void startNatsConsumer(Workspace workspace) {
        if (natsProperties.enabled()) {
            natsConsumerService.ifAvailable(svc -> svc.startConsumingScope(workspace.getId()));
        }
    }
}
