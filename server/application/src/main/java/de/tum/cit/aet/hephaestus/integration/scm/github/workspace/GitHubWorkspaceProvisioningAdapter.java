package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class GitHubWorkspaceProvisioningAdapter implements ProvisioningListener {

    private static final Logger log = LoggerFactory.getLogger(GitHubWorkspaceProvisioningAdapter.class);

    private final GitHubLifecycleListener githubLifecycleListener;
    private final WorkspaceRepositoryMonitorService repositoryMonitorService;
    private final WorkspaceScopeFilter workspaceScopeFilter;

    private final GitHubWorkspaceDataSyncTrigger dataSyncTrigger;
    private final AsyncTaskExecutor monitoringExecutor;

    public GitHubWorkspaceProvisioningAdapter(
            GitHubLifecycleListener githubLifecycleListener,
            WorkspaceRepositoryMonitorService repositoryMonitorService,
            WorkspaceScopeFilter workspaceScopeFilter,
            GitHubWorkspaceDataSyncTrigger dataSyncTrigger,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor) {
        this.githubLifecycleListener = githubLifecycleListener;
        this.repositoryMonitorService = repositoryMonitorService;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.dataSyncTrigger = dataSyncTrigger;
        this.monitoringExecutor = monitoringExecutor;
    }

    @Override
    public void onInstallationCreated(InstallationData installation) {
        if (installation == null) {
            log.warn("Skipped workspace provisioning: reason=nullInstallationData");
            return;
        }

        String accountLogin = installation.accountLogin();
        if (accountLogin == null || !workspaceScopeFilter.isOrganizationAllowed(accountLogin)) {
            log.debug(
                    "Skipped workspace provisioning: reason=organizationFiltered, accountLogin={}, installationId={}",
                    installation.accountLogin(),
                    installation.installationId());
            return;
        }

        RepositorySelection selection = RepositorySelection.SELECTED;
        Workspace workspace = githubLifecycleListener.createOrUpdateFromInstallation(
                installation.installationId(),
                installation.accountId(),
                installation.accountLogin(),
                mapAccountKind(installation.accountType()),
                installation.avatarUrl(),
                selection);

        if (workspace == null) {
            log.warn(
                    "Skipped installation event: reason=workspaceCreationFailed, installationId={}",
                    installation.installationId());
            return;
        }

        // GitHub includes the initial repository list on "created" events with "selected" repository
        // selection; materialise Repository entities and monitors for each from that webhook metadata.
        if (installation.repositories() != null && !installation.repositories().isEmpty()) {
            List<RepositorySnapshot> allowedRepos = installation.repositories().stream()
                    .filter(r -> workspaceScopeFilter.isRepositoryAllowed(r.nameWithOwner()))
                    .toList();

            if (allowedRepos.size() < installation.repositories().size()) {
                log.debug(
                        "Filtered initial repositories by scope: installationId={}, allowed={}, total={}",
                        installation.installationId(),
                        allowedRepos.size(),
                        installation.repositories().size());
            }

            if (!allowedRepos.isEmpty()) {
                log.info(
                        "Adding initial repositories: installationId={}, repoCount={}",
                        installation.installationId(),
                        allowedRepos.size());
                for (RepositorySnapshot snapshot : allowedRepos) {
                    repositoryMonitorService.ensureRepositoryAndMonitorFromSnapshot(
                            installation.installationId(), snapshot);
                }
            }
        }
    }

    @Override
    public void onInstallationDeleted(@Nullable Long installationId) {
        if (installationId == null) {
            log.warn("Skipped installation deletion: reason=nullInstallationId");
            return;
        }

        // Delegates to the full purge chain rather than a narrower stop-NATS/remove-monitors pair:
        // purgeWorkspace first stops the NATS consumer, then ScmWorkspacePurgeAdapter (order -200) drops
        // the monitors through an orphan-guarded cascade — unlike removeAllRepositoriesFromMonitor, it
        // will not delete a repository another workspace still monitors. The chain also tears down
        // Slack/Outline content, org-tier teams and organization memberships, derived practice/activity
        // rows, and the Connection's stored credentials.
        githubLifecycleListener.purgeWorkspaceForInstallation(installationId);

        log.info("Completed installation cleanup: installationId={}", installationId);
    }

    @Override
    public void onRepositoriesAdded(Long installationId, List<RepositorySnapshot> repositories) {
        if (installationId == null || repositories == null || repositories.isEmpty()) {
            log.debug(
                    "Skipped repositories added event: reason=missingData, installationId={}, hasRepositories={}",
                    installationId,
                    repositories != null && !repositories.isEmpty());
            return;
        }

        List<RepositorySnapshot> allowedRepos = repositories.stream()
                .filter(r -> workspaceScopeFilter.isRepositoryAllowed(r.nameWithOwner()))
                .toList();

        if (allowedRepos.size() < repositories.size()) {
            log.debug(
                    "Filtered repositories by scope: installationId={}, allowed={}, total={}",
                    installationId,
                    allowedRepos.size(),
                    repositories.size());
        }

        if (allowedRepos.isEmpty()) {
            log.debug("No repositories to add after filtering: installationId={}", installationId);
            return;
        }

        for (RepositorySnapshot snapshot : allowedRepos) {
            repositoryMonitorService.ensureRepositoryAndMonitorFromSnapshot(installationId, snapshot);
        }
        log.info("Added repositories to monitor: installationId={}, repoCount={}", installationId, allowedRepos.size());
    }

    @Override
    public void onRepositoriesRemoved(Long installationId, List<String> repositoryNames) {
        if (installationId == null || repositoryNames == null || repositoryNames.isEmpty()) {
            log.debug(
                    "Skipped repositories removed event: reason=missingData, installationId={}, hasRepositories={}",
                    installationId,
                    repositoryNames != null && !repositoryNames.isEmpty());
            return;
        }

        for (String nameWithOwner : repositoryNames) {
            repositoryMonitorService.removeRepositoryMonitorForInstallation(installationId, nameWithOwner);
        }
        log.info(
                "Removed repositories from monitor: installationId={}, repoCount={}",
                installationId,
                repositoryNames.size());
    }

    @Override
    public void onAccountRenamed(Long installationId, @Nullable String oldLogin, String newLogin) {
        if (installationId == null || newLogin == null || newLogin.isBlank()) {
            log.warn(
                    "Skipped account rename: reason=invalidData, installationId={}, oldLogin={}, newLogin={}",
                    installationId,
                    oldLogin,
                    newLogin);
            return;
        }

        githubLifecycleListener.handleAccountRename(installationId, oldLogin, newLogin);
    }

    @Override
    public void onInstallationSuspended(Long installationId) {
        if (installationId == null) {
            log.warn("Skipped installation suspension: reason=nullInstallationId");
            return;
        }

        githubLifecycleListener.stopNatsForInstallation(installationId);
        githubLifecycleListener.updateWorkspaceStatus(installationId, Workspace.WorkspaceStatus.SUSPENDED);
        log.info("Suspended installation: installationId={}", installationId);
    }

    @Override
    public void onInstallationActivated(Long installationId) {
        if (installationId == null) {
            log.warn("Skipped installation activation: reason=nullInstallationId");
            return;
        }

        githubLifecycleListener
                .updateWorkspaceStatus(installationId, Workspace.WorkspaceStatus.ACTIVE)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .ifPresent(workspace -> {
                    Long workspaceId = workspace.getId();
                    afterCommit(() -> triggerInitialSync(installationId, workspaceId));
                    githubLifecycleListener.startNatsForInstallation(installationId);
                });

        log.info("Activated installation: installationId={}", installationId);
    }

    @Override
    public void onRepositorySelectionChanged(Long installationId, String selection) {
        if (installationId == null) {
            log.warn("Skipped repository selection change: reason=nullInstallationId");
            return;
        }

        RepositorySelection repoSelection = parseRepositorySelection(selection);
        githubLifecycleListener.updateRepositorySelection(installationId, repoSelection);
        log.debug("Updated repository selection: installationId={}, selection={}", installationId, repoSelection);
    }

    private void triggerInitialSync(Long installationId, Long workspaceId) {
        log.info(
                "Starting initial sync for activated installation: installationId={}, workspaceId={}",
                installationId,
                workspaceId);

        monitoringExecutor.execute(() -> {
            try {
                dataSyncTrigger.syncAllRepositories(workspaceId);
                log.info(
                        "Completed initial sync for activated installation: installationId={}, workspaceId={}",
                        installationId,
                        workspaceId);
            } catch (Exception e) {
                log.error(
                        "Failed initial sync for activated installation: installationId={}, workspaceId={}",
                        installationId,
                        workspaceId,
                        e);
            }
        });
    }

    private RepositorySelection parseRepositorySelection(String selection) {
        if ("all".equalsIgnoreCase(selection)) {
            return RepositorySelection.ALL;
        }
        return RepositorySelection.SELECTED;
    }

    /**
     * Bridges the GitHub-shaped {@link ProvisioningListener.AccountType} (2 values) to the
     * framework-wide {@link IntegrationLifecycleListener.AccountKind} (3 values, including
     * {@code TEAM_WORKSPACE} for Slack) — the third value is unreachable from the GitHub SPI surface.
     */
    private static IntegrationLifecycleListener.AccountKind mapAccountKind(ProvisioningListener.AccountType type) {
        if (type == null) {
            return IntegrationLifecycleListener.AccountKind.ORGANIZATION;
        }
        return switch (type) {
            case ORGANIZATION -> IntegrationLifecycleListener.AccountKind.ORGANIZATION;
            case USER -> IntegrationLifecycleListener.AccountKind.USER;
        };
    }
}
