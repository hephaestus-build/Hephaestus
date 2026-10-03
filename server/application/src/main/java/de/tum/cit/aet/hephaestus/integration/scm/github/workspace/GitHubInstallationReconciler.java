package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProvisioningHook;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reconciles GitHub App installations against the workspace registry.
 *
 * <p>At startup (and on-demand), enumerates every installation the configured App can see
 * and ensures each one maps to an active workspace — creating, suspending, or repointing
 * workspaces as the App's installation list changes. Lives in the GitHub adapter so the
 * workspace module never imports {@link GitHubAppTokenService} or speaks the
 * {@code /app/installations} payload shape.
 *
 * <p>Intentionally not {@code @Transactional}: per-installation reconciliation runs in
 * isolated paths so one failed installation doesn't roll back the rest. Errors are logged
 * and swallowed; the worst case is a stale workspace, which the next startup retry fixes.
 */
@Service
public class GitHubInstallationReconciler implements WorkspaceProvisioningHook {

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public void reconcile() {
        ensureGitHubAppInstallations();
    }

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationReconciler.class);

    private final GitHubAppTokenService gitHubAppTokenService;
    private final GitHubLifecycleListener githubLifecycleListener;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final WebClient webClient;

    public GitHubInstallationReconciler(
            GitHubAppTokenService gitHubAppTokenService,
            GitHubLifecycleListener githubLifecycleListener,
            WorkspaceRepository workspaceRepository,
            WorkspaceService workspaceService,
            WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService,
            WorkspaceScopeFilter workspaceScopeFilter) {
        this.gitHubAppTokenService = gitHubAppTokenService;
        this.githubLifecycleListener = githubLifecycleListener;
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.workspaceRepositoryMonitorService = workspaceRepositoryMonitorService;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.webClient = WebClient.builder()
                .clientConnector(WebClientConnectors.systemDns())
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Enumerates GitHub App installations and ensures each has a corresponding workspace.
     *
     * <p>No-op if the App is unconfigured. Per-installation failures are logged but do not
     * abort the loop.
     */
    public void ensureGitHubAppInstallations() {
        if (!gitHubAppTokenService.isConfigured()) {
            log.info(
                    "Skipped GitHub App installation processing: reason=credentialsNotConfigured, appId={}",
                    gitHubAppTokenService.getConfiguredAppId());
            return;
        }

        try {
            String appJwt = gitHubAppTokenService.generateAppJWT();

            AppInfoResponse appInfo = webClient
                    .get()
                    .uri("/app")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .bodyToMono(AppInfoResponse.class)
                    .block();

            if (appInfo == null) {
                log.warn("Skipped GitHub App processing: reason=nullAppInfoResponse");
                return;
            }

            String ownerLogin = appInfo.owner() != null ? appInfo.owner().login() : "unknown";
            log.info(
                    "Authenticated as GitHub App: appName={}, appSlug={}, appId={}, ownerLogin={}",
                    appInfo.name(),
                    appInfo.slug(),
                    appInfo.id(),
                    ownerLogin);

            List<InstallationDto> installations = webClient
                    .get()
                    .uri("/app/installations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .bodyToFlux(InstallationDto.class)
                    .collectList()
                    .block();

            if (installations == null || installations.isEmpty()) {
                log.warn(
                        "Skipped GitHub App processing: reason=noInstallations, appSlug={}, appId={}",
                        appInfo.slug(),
                        appInfo.id());
                return;
            }

            log.info(
                    "Ensured GitHub App installations reflected as workspaces: installationCount={}",
                    installations.size());

            for (InstallationDto installation : installations) {
                String accountLogin =
                        installation.account() != null ? installation.account().login() : "<unknown>";
                log.info(
                        "Processed GitHub App installation: installationId={}, accountLogin={}, selection={}",
                        installation.id(),
                        accountLogin,
                        installation.repositorySelection());
                synchronizeInstallation(installation);
            }
        } catch (Exception e) {
            log.warn("Failed to reconcile GitHub App installations: reason=apiError", e);
        }
    }

    private void synchronizeInstallation(InstallationDto installation) {
        if (installation.account() == null) {
            log.warn("Skipped installation sync: reason=noAccountInfo, installationId={}", installation.id());
            return;
        }

        if (installation.suspendedAt() != null) {
            log.info("Skipped installation sync: reason=suspended, installationId={}", installation.id());
            gitHubAppTokenService.markInstallationSuspended(installation.id());
            workspaceRepository.findByInstallationId(installation.id()).ifPresent(ws -> {
                if (ws.getStatus() != Workspace.WorkspaceStatus.SUSPENDED) {
                    githubLifecycleListener.updateWorkspaceStatus(
                            installation.id(), Workspace.WorkspaceStatus.SUSPENDED);
                }
            });
            return;
        }
        gitHubAppTokenService.markInstallationActive(installation.id());

        String login = installation.account().login();

        if (workspaceScopeFilter.isActive() && !workspaceScopeFilter.isOrganizationAllowed(login)) {
            log.info(
                    "Skipped installation sync: reason=filteredByScope, installationId={}, accountLogin={}",
                    installation.id(),
                    login);
            return;
        }

        String accountType = installation.account().type();
        long installationId = installation.id();
        RepositorySelection selection = convertRepositorySelection(installation.repositorySelection());

        log.info(
                "Ensured installation workspace: installationId={}, orgLogin={}, selection={}",
                installationId,
                login,
                selection);

        var account = installation.account();
        IntegrationLifecycleListener.AccountKind wsAccountKind = "Organization".equalsIgnoreCase(accountType)
                ? IntegrationLifecycleListener.AccountKind.ORGANIZATION
                : IntegrationLifecycleListener.AccountKind.USER;

        Workspace workspace = githubLifecycleListener.createOrUpdateFromInstallation(
                installationId, account.id(), login, wsAccountKind, account.avatarUrl(), selection);

        if (workspace == null) {
            log.warn(
                    "Skipped workspace creation: reason=userNotFound, installationId={}, orgLogin={}",
                    installationId,
                    login);
            return;
        }

        workspace = workspaceService.updateAccountLogin(workspace.getId(), login);

        log.info("Configured organization sync via webhooks: workspaceSlug={}", workspace.getWorkspaceSlug());

        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(installationId, null, true);
    }

    private @Nullable RepositorySelection convertRepositorySelection(@Nullable String selection) {
        if (selection == null) {
            return null;
        }
        return switch (selection.toLowerCase()) {
            case "all" -> RepositorySelection.ALL;
            case "selected" -> RepositorySelection.SELECTED;
            default -> null;
        };
    }

    // DTOs for GitHub REST API

    private record AppInfoResponse(long id, String name, String slug, OwnerDto owner) {}

    private record OwnerDto(String login) {}

    private record InstallationDto(
            long id,
            AccountDto account,
            @JsonProperty("repository_selection") String repositorySelection,
            @JsonProperty("suspended_at") Instant suspendedAt) {}

    private record AccountDto(
            Long id,
            String login,
            String type,
            @JsonProperty("avatar_url") String avatarUrl) {}
}
