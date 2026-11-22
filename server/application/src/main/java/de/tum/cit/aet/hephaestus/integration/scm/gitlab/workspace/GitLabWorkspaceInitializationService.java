package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabSyncReports;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
 *   <li>Group project sync via GraphQL (discovers all projects in group + subgroups)</li>
 *   <li>Organization linking (workspace → synced Organization entity)</li>
 *   <li>Repository monitor creation (RepositoryToMonitor entries for each project)</li>
 *   <li>Webhook registration (idempotent)</li>
 * </ol>
 *
 * <p>The webhook comes last because provider inbox skips an event published before a consumer's filter
 * covers its repository, and a handler skips an event whose repository has no row yet. Neither is
 * redelivered, so GitLab may only start delivering once both exist.
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
    private final WorkspaceLookup workspaceLookup;

    // The group project sync is gated by @ConditionalOnProperty: null while GitLab is disabled
    private final @Nullable GitLabGroupSyncService groupSyncService;

    // Lazy-loaded: optional GitLab beans gated by @ConditionalOnProperty
    private final ObjectProvider<GitLabWebhookService> gitLabWebhookServiceProvider;
    private final ObjectProvider<GitLabWorkspaceDataSyncTrigger> dataSyncTriggerProvider;

    // Authoritative source for per-workspace integration config (server URL, PAT presence).
    private final ConnectionService connectionService;
    private final GitLabRepositoryMonitors repositoryMonitors;
    private final GitLabWorkspaceLinkService workspaceLinkService;

    // Infrastructure
    private final AsyncTaskExecutor monitoringExecutor;

    public GitLabWorkspaceInitializationService(
            WorkspaceLookup workspaceLookup,
            @Nullable GitLabGroupSyncService groupSyncService,
            ObjectProvider<GitLabWebhookService> gitLabWebhookServiceProvider,
            ObjectProvider<GitLabWorkspaceDataSyncTrigger> dataSyncTriggerProvider,
            ConnectionService connectionService,
            GitLabWorkspaceLinkService workspaceLinkService,
            GitLabRepositoryMonitors repositoryMonitors,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.workspaceLookup = workspaceLookup;
        this.groupSyncService = groupSyncService;
        this.gitLabWebhookServiceProvider = gitLabWebhookServiceProvider;
        this.dataSyncTriggerProvider = dataSyncTriggerProvider;
        this.connectionService = connectionService;
        this.workspaceLinkService = workspaceLinkService;
        this.repositoryMonitors = repositoryMonitors;
        this.monitoringExecutor = monitoringExecutor;
    }

    /**
     * Triggers GitLab workspace initialization asynchronously. Returns immediately so the
     * caller's HTTP response is not blocked; discovery, webhook and full sync run on
     * the monitoring executor, in that order.
     */
    public void initializeAsync(Long workspaceId) {
        monitoringExecutor.execute(() -> {
            try {
                Workspace workspace = workspaceLookup.find(workspaceId).orElse(null);
                if (workspace == null) {
                    log.warn(
                            "Skipped async GitLab initialization: reason=workspaceNotFound, workspaceId={}",
                            workspaceId);
                    return;
                }

                // Set workspace context for entire lifecycle (init + sync).
                // initialize() checks contextOwner and won't double-set/clear.
                // GitLab workspaces never have a GitHub App installation id.
                WorkspaceContext context =
                        WorkspaceContext.fromWorkspace(workspace, Set.of(), /* installationId */ null);
                WorkspaceContextHolder.setContext(context);
                try {
                    dataSyncTriggerProvider.getObject().syncAllRepositories(workspaceId);
                } finally {
                    WorkspaceContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.error("Failed async GitLab workspace initialization: workspaceId={}", workspaceId, e);
            }
        });
    }

    /**
     * Runs the core GitLab initialization sequence for a workspace: project discovery,
     * organization linking, monitor creation and webhook setup.
     *
     * <p>The lifecycle data sync trigger calls this before the full data sync, including activation
     * submitted through {@link #initializeAsync(Long)}. The webhook retry also calls this directly.
     *
     * <p>Each phase is isolated: a failed or partial discovery still monitors and routes the
     * repositories it found. The webhook opens only after a complete discovery and a confirmed
     * consumer; otherwise it stays as it was, and {@link #initializeIfWebhookMissing} retries.
     *
     * @param workspace the GITLAB_PAT workspace to initialize
     * @return discovery and setup outcome, with the number of projects discovered
     */
    public SyncResult initialize(Workspace workspace) {
        return initialize(workspace, null);
    }

    public SyncResult initialize(Workspace workspace, @Nullable SyncExecutionHandle handle) {
        var gitLabConfigOpt = connectionService.findActiveGitLabConfig(workspace.getId());
        if (gitLabConfigOpt.isEmpty()) {
            return SyncResult.completed(0);
        }

        boolean hasToken = connectionService
                .findActiveBearerToken(workspace.getId(), IntegrationKind.GITLAB)
                .map(b -> b.token() != null && !b.token().isBlank())
                .orElse(false);
        if (!hasToken) {
            log.warn("Skipped GitLab initialization: reason=missingToken, workspaceId={}", workspace.getId());
            reportIncomplete(handle, SyncResult.abortedError(0), SyncPhase.CONNECTION, "Connection token check", null);
            return SyncResult.abortedError(0);
        }

        String accountLogin = workspace.getAccountLogin();
        if (accountLogin == null || accountLogin.isBlank()) {
            log.warn("Skipped GitLab initialization: reason=missingAccountLogin, workspaceId={}", workspace.getId());
            reportIncomplete(handle, SyncResult.abortedError(0), SyncPhase.CONNECTION, "Connected group check", null);
            return SyncResult.abortedError(0);
        }

        // Only set context if not already set (avoids clearing caller's context).
        // GitLab workspaces never have a GitHub App installation id.
        boolean contextOwner = WorkspaceContextHolder.getContext() == null;
        if (contextOwner) {
            WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, Set.of(), /* installationId */ null);
            WorkspaceContextHolder.setContext(context);
        }
        try {
            log.info("Starting GitLab workspace initialization: workspaceId={}", workspace.getId());

            // Phase 0: Token rotation
            SyncResult result = rotateToken(workspace, handle);

            // Phase 1: Discover group projects via GraphQL. A credential that GitLab already refused is a known
            // state that the admin sees as an attention problem, so discovery does not ask GitLab again. The
            // token inspection above still runs: it is the call that sees the credential accepted again.
            boolean credentialRefused = connectionService
                    .findActive(workspace.getId(), IntegrationKind.GITLAB)
                    .filter(connection -> connection.getAttentionProblem() == Problem.CREDENTIAL_REVOKED)
                    .isPresent();
            if (credentialRefused) {
                log.atWarn()
                        .addKeyValue("event.name", "integration.credential.refused")
                        .addKeyValue("integration.kind", "gitlab")
                        .addKeyValue("workspace.id", workspace.getId())
                        .log("Skipped GitLab project discovery, the credential is refused until a reconnect");
            }
            GitLabSyncResult discovery = credentialRefused ? null : discoverGroupProjects(workspace);
            List<Repository> syncedRepos = discovery == null ? List.of() : discovery.synced();
            SyncResult discoveryResult = discovery == null
                    ? SyncResult.abortedError(0)
                    : switch (discovery.status()) {
                        case COMPLETED -> SyncResult.completed(syncedRepos.size());
                        case COMPLETED_WITH_ERRORS -> SyncResult.completedWithWarnings(syncedRepos.size());
                        case ABORTED_RATE_LIMIT -> SyncResult.abortedRateLimit(syncedRepos.size());
                        case ABORTED_ERROR -> SyncResult.abortedError(syncedRepos.size());
                    };
            reportIncomplete(handle, discoveryResult, SyncPhase.REPOSITORIES, "Project discovery", accountLogin);
            result = SyncResult.merge(result, discoveryResult);

            // Phase 2: Link organization + create monitors
            if (!syncedRepos.isEmpty()) {
                linkWorkspaceToOrganization(workspace);
                repositoryMonitors.monitorAll(workspace, syncedRepos);
            }

            // Phase 3: Let GitLab deliver. The receiver stores each delivery for the connection's workspace.
            boolean discovered = discovery != null && discovery.status() == GitLabSyncResult.Status.COMPLETED;
            // A refused credential already has its event, so it does not log a second cause.
            if (!discovered && !credentialRefused) {
                log.warn(
                        "Skipped GitLab webhook registration: reason=discoveryIncomplete, workspaceId={}, status={}",
                        workspace.getId(),
                        discovery == null ? null : discovery.status());
            } else if (discovered) {
                SyncResult webhookResult = registerWebhook(workspace);
                reportIncomplete(handle, webhookResult, SyncPhase.CONNECTION, "Webhook setup", null);
                result = SyncResult.merge(result, webhookResult);
            }

            log.info(
                    "Completed GitLab workspace initialization: workspaceId={}, repos={}",
                    workspace.getId(),
                    syncedRepos.size());
            return result;
        } finally {
            if (contextOwner) {
                WorkspaceContextHolder.clearContext();
            }
        }
    }

    /**
     * Runs {@link #initialize} again when the workspace's webhook should exist but was never registered, because an
     * earlier initialization found discovery incomplete or the consumer not ready. A workspace on project hooks
     * instead registers the hooks of projects monitored since, and releases those of projects no longer monitored.
     *
     * @return whether the webhook is still missing afterwards
     */
    public boolean initializeIfWebhookMissing(long workspaceId) {
        var webhookService = gitLabWebhookServiceProvider.getIfAvailable();
        boolean projectHooks = connectionService
                .findActiveGitLabConfig(workspaceId)
                .filter(config -> !config.projectHooks().isEmpty())
                .isPresent();
        if (projectHooks && webhookService != null && webhookService.isRegistrationEnabled()) {
            // Project hooks follow the monitors: a new project gets its hook, an unmonitored one loses it.
            return workspaceLookup
                    .find(workspaceId)
                    .map(workspace -> !webhookService.registerWebhook(workspace).registered())
                    .orElse(false);
        }
        if (!isWebhookMissing(workspaceId)) {
            return false;
        }
        log.info("Retrying GitLab initialization: reason=webhookMissing, workspaceId={}", workspaceId);
        workspaceLookup.find(workspaceId).ifPresent(this::initialize);
        return isWebhookMissing(workspaceId);
    }

    private boolean isWebhookMissing(long workspaceId) {
        var webhookService = gitLabWebhookServiceProvider.getIfAvailable();
        return webhookService != null
                && webhookService.isRegistrationEnabled()
                && connectionService
                        .findActiveGitLabConfig(workspaceId)
                        // GitLab refused every hook: the health check asks again, not each sync's initialization.
                        .map(config -> config.gitlabWebhookId() == null
                                && config.projectHooks().isEmpty()
                                && config.webhooksRefusedAt() == null)
                        .orElse(false);
    }

    /** Rotates the token when it expires soon; a failed attempt contributes warnings without stopping discovery. */
    private SyncResult rotateToken(Workspace workspace, @Nullable SyncExecutionHandle handle) {
        var webhookService = gitLabWebhookServiceProvider.getIfAvailable();
        if (webhookService == null) {
            return SyncResult.completed(0);
        }
        try {
            webhookService.rotateTokenIfNeeded(workspace);
            return SyncResult.completed(0);
        } catch (Exception e) {
            log.warn("Token rotation failed (non-fatal): workspaceId={}", workspace.getId(), e);
            if (handle != null) {
                handle.reportWarnings(SyncProgress.of(SyncPhase.CONNECTION, "Token rotation did not complete."));
            }
            return SyncResult.completedWithWarnings(0);
        }
    }

    /** Registers the group webhook when enabled; a failed attempt contributes a non-clean setup outcome. */
    private SyncResult registerWebhook(Workspace workspace) {
        var webhookService = gitLabWebhookServiceProvider.getIfAvailable();
        if (webhookService == null) {
            return SyncResult.completed(0);
        }
        try {
            if (!webhookService.isRegistrationEnabled()) {
                return SyncResult.completed(0);
            }
            var webhookResult = webhookService.registerWebhook(workspace);
            if (webhookResult.registered()) {
                log.info(
                        "Webhook setup: workspaceId={}, registered=true, webhookId={}",
                        workspace.getId(),
                        webhookResult.webhookId());
                return SyncResult.completed(0);
            } else {
                if (webhookResult.status() == WebhookSetupResult.Status.SKIPPED) {
                    log.warn(
                            "Webhook setup: workspaceId={}, registered=false, reason={}",
                            workspace.getId(),
                            webhookResult.reason());
                }
                return SyncResult.abortedError(0);
            }
        } catch (Exception e) {
            log.warn("Webhook registration failed (non-fatal): workspaceId={}", workspace.getId(), e);
            return SyncResult.abortedError(0);
        }
    }

    /**
     * Discovers all projects in the workspace's GitLab group via GraphQL.
     *
     * @return the discovery result, or {@code null} when discovery could not run or failed
     */
    private @Nullable GitLabSyncResult discoverGroupProjects(Workspace workspace) {
        if (groupSyncService == null) {
            log.warn(
                    "Skipped GitLab project discovery: reason=syncServiceUnavailable, workspaceId={}",
                    workspace.getId());
            return null;
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
                    groupSyncService.syncGroupProjects(workspace.getId(), workspace.getAccountLogin(), serverUrl);
            log.info(
                    "GitLab project discovery: workspaceId={}, status={}, synced={}, failed={}, pages={}",
                    workspace.getId(),
                    result.status(),
                    result.synced().size(),
                    result.projectsSkipped(),
                    result.pagesCompleted());
            return result;
        } catch (Exception e) {
            log.error("Failed GitLab project discovery: workspaceId={}", workspace.getId(), e);
            return null;
        }
    }

    public void linkWorkspaceToOrganization(Workspace workspace) {
        workspaceLinkService.link(workspace);
    }

    private static void reportIncomplete(
            @Nullable SyncExecutionHandle handle,
            SyncResult result,
            SyncPhase phase,
            String operation,
            @Nullable String resource) {
        GitLabSyncReports.warn(handle, result.status(), phase, operation, resource);
    }
}
