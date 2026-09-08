package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Activates workspaces on application startup: metadata population, sync orchestration,
 * and NATS consumer initialization.
 */
@Service
public class WorkspaceActivationService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceActivationService.class);

    private final NatsConnectionProperties natsProperties;
    private final SyncSchedulerProperties syncSchedulerProperties;

    private final WorkspaceRepository workspaceRepository;

    // natsConsumerService is absent under the webhook profile (server.enabled=false);
    // activation paths that need it are themselves server-only.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final ConnectionService connectionService;

    /**
     * Per-kind sync triggers, collected through the SPI so workspace activation never
     * imports a specific provider's sync service. An unmapped {@link IntegrationKind} is
     * treated as "no sync needed" rather than throwing.
     */
    private final Map<IntegrationKind, WorkspaceDataSyncTrigger> dataSyncTriggers;

    private final AsyncTaskExecutor monitoringExecutor;

    public WorkspaceActivationService(
            NatsConnectionProperties natsProperties,
            SyncSchedulerProperties syncSchedulerProperties,
            WorkspaceRepository workspaceRepository,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            List<WorkspaceDataSyncTrigger> dataSyncTriggerList,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.natsProperties = natsProperties;
        this.syncSchedulerProperties = syncSchedulerProperties;
        this.workspaceRepository = workspaceRepository;
        this.natsConsumerService = natsConsumerService;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        Map<IntegrationKind, WorkspaceDataSyncTrigger> map = new EnumMap<>(IntegrationKind.class);
        for (WorkspaceDataSyncTrigger t : dataSyncTriggerList) {
            map.put(t.kind(), t);
        }
        this.dataSyncTriggers = map;
        this.monitoringExecutor = monitoringExecutor;
    }

    /**
     * Prepare every workspace and start monitoring/sync routines for those that are ready.
     * Runs after provisioning so the workspace catalog is populated; the installation
     * consumer is already running by then (it only needs workspaces to exist, not the full sync).
     */
    public void activateAllWorkspaces() {
        List<Workspace> workspaces = workspaceRepository.findAll();
        if (workspaces.isEmpty()) {
            log.info("No workspaces found on startup; waiting for GitHub App backfill or manual provisioning.");
            return;
        }

        List<Workspace> prepared = new ArrayList<>(workspaces.size());
        for (Workspace workspace : workspaces) {
            prepared.add(ensureWorkspaceMetadata(workspace));
        }

        List<Workspace> workspacesToActivate = prepared.stream()
                .filter(workspace -> !shouldSkipActivation(workspace))
                .toList();

        if (workspacesToActivate.isEmpty()) {
            log.info("No workspaces to activate after filtering");
            return;
        }

        log.info("Activating workspaces: count={}", workspacesToActivate.size());

        List<CompletableFuture<Void>> activationFutures = workspacesToActivate.stream()
                .map(workspace -> CompletableFuture.runAsync(() -> activateWorkspace(workspace), monitoringExecutor)
                        .exceptionally(ex -> {
                            // Catches errors outside activateWorkspace itself, e.g. thread pool rejection.
                            log.error(
                                    "Unexpected error during workspace activation: workspaceId={}, accountLogin={}",
                                    workspace.getId(),
                                    workspace.getAccountLogin(),
                                    ex);
                            return null;
                        }))
                .toList();

        CompletableFuture.allOf(activationFutures.toArray(CompletableFuture[]::new))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Workspace activation completed with errors", ex);
                    } else {
                        log.info("Completed workspace activations: count={}", workspacesToActivate.size());
                    }
                });
    }

    /**
     * Checks if a workspace should skip activation based on its configuration and status.
     *
     * <p>The PAT-bearing variants (GitHub PAT, GitLab PAT) require a bearer credential blob;
     * the GitHub App variant mints installation tokens on demand, so its credential blob is
     * intentionally empty and must not gate activation.
     */
    private boolean shouldSkipActivation(Workspace workspace) {
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
            log.info(
                    "Skipped workspace activation: reason=notActive, workspaceId={}, status={}",
                    workspace.getId(),
                    workspace.getStatus());
            return true;
        }

        var providerKind = connectionService.findActiveProviderKind(workspace.getId());
        if (providerKind.isEmpty()) {
            // No active SCM connection — nothing to sync until one is bound.
            return false;
        }
        return switch (providerKind.get()) {
            case GITHUB -> {
                if (connectionService
                                .findActiveGitHubPatConfig(workspace.getId())
                                .isPresent()
                        && !hasBearerToken(workspace.getId(), IntegrationKind.GITHUB)) {
                    log.info(
                            "Skipped workspace activation: reason=patModeWithoutToken, workspaceId={}",
                            workspace.getId());
                    yield true;
                }
                yield false;
            }
            case GITLAB -> {
                if (!hasBearerToken(workspace.getId(), IntegrationKind.GITLAB)) {
                    log.info(
                            "Skipped workspace activation: reason=gitlabPatModeWithoutToken, workspaceId={}",
                            workspace.getId());
                    yield true;
                }
                yield false;
            }
            case SLACK, OUTLINE, KEYCLOAK_DIRECTORY -> false;
        };
    }

    private boolean hasBearerToken(long workspaceId, IntegrationKind kind) {
        return connectionService
                .findActiveBearerToken(workspaceId, kind)
                .map(b -> b.token() != null && !b.token().isBlank())
                .orElse(false);
    }

    /** Activate a single workspace: run startup sync, then start its NATS consumer scope. */
    public void activateWorkspace(Workspace workspace) {
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
            log.debug(
                    "Skipped workspace activation: reason=notActive, workspaceId={}, status={}",
                    workspace.getId(),
                    workspace.getStatus());
            return;
        }

        if (!workspaceScopeFilter.isWorkspaceAllowed(workspace)) {
            log.info("Skipped workspace activation: reason=filteredByScope, workspaceId={}", workspace.getId());
            return;
        }

        if (syncSchedulerProperties.runOnStartup()) {
            log.info("Starting monitoring on startup: workspaceId={}", workspace.getId());

            // Set workspace context for the sync operations (enables MDC logging).
            Long installationId = connectionService
                    .findActiveGitHubAppConfig(workspace.getId())
                    .map(ConnectionConfig.GitHubAppConfig::installationId)
                    .orElse(null);
            WorkspaceContext workspaceContext = WorkspaceContext.fromWorkspace(workspace, Set.of(), installationId);
            WorkspaceContextHolder.setContext(workspaceContext);
            try {
                IntegrationKind kind = connectionService
                        .findActiveProviderKind(workspace.getId())
                        .orElse(IntegrationKind.GITHUB);
                WorkspaceDataSyncTrigger trigger = dataSyncTriggers.get(kind);
                if (trigger == null) {
                    log.debug(
                            "Skipped startup sync: reason=noTriggerForKind, workspaceId={}, kind={}",
                            workspace.getId(),
                            kind);
                } else {
                    trigger.syncAllRepositories(workspace.getId());
                }

                log.info("Completed monitoring on startup: workspaceId={}", workspace.getId());
            } catch (Exception e) {
                // Continue to start the NATS consumer so webhook events can still be processed;
                // entities missing from the failed sync are handled via NAK/retry.
                log.error(
                        "Failed monitoring on startup: workspaceId={}, accountLogin={}, error={}",
                        workspace.getId(),
                        workspace.getAccountLogin(),
                        e.getMessage(),
                        e);
            } finally {
                WorkspaceContextHolder.clearContext();
            }
        }

        // Start the NATS consumer AFTER startup sync so webhook events never reference
        // entities the sync has not created yet.
        if (shouldUseNats(workspace)) {
            natsConsumerService.ifAvailable(svc -> svc.startConsumingScope(workspace.getId()));
        }
    }

    /** Ensures workspace metadata (account login) is populated, persisting when it was derived. */
    public Workspace ensureWorkspaceMetadata(Workspace workspace) {
        if (isBlank(workspace.getAccountLogin())) {
            String derived = deriveAccountLogin(workspace);
            if (!isBlank(derived)) {
                workspace.setAccountLogin(Objects.requireNonNull(derived));
                return workspaceRepository.save(workspace);
            }
        }

        return workspace;
    }

    /** Derives an account login for legacy workspaces from their first monitored repo's owner. */
    @Nullable
    String deriveAccountLogin(Workspace workspace) {
        String repoOwner = workspace.getRepositoriesToMonitor().stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .map(this::extractOwner)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (repoOwner != null && !repoOwner.isBlank()) {
            return Objects.requireNonNull(repoOwner);
        }

        return null;
    }

    private @Nullable String extractOwner(@Nullable String nameWithOwner) {
        if (nameWithOwner == null || nameWithOwner.isBlank()) {
            return null;
        }
        int idx = nameWithOwner.indexOf('/');
        if (idx <= 0) {
            return null;
        }
        return nameWithOwner.substring(0, idx);
    }

    private boolean shouldUseNats(Workspace workspace) {
        return natsProperties.enabled() && workspace != null;
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
