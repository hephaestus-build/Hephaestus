package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProvisioningHook;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequests;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 * {@code /app/installations} payload shape. It does not enumerate an installation's repositories:
 * workspace activation reconciles them later, so a slow installation does not delay the other
 * workspaces.
 *
 * <p>Intentionally not {@code @Transactional}: per-installation reconciliation runs in
 * isolated paths so one failed installation doesn't roll back the rest. Errors are logged
 * and swallowed; the worst case is a stale workspace, which the next startup retry fixes.
 */
@Service
@ConditionalOnServerRole
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
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final GitHubInstallationRequests installationRequests;
    private final WebClient webClient;

    public GitHubInstallationReconciler(
            GitHubAppTokenService gitHubAppTokenService,
            GitHubLifecycleListener githubLifecycleListener,
            WorkspaceRepository workspaceRepository,
            WorkspaceService workspaceService,
            WorkspaceScopeFilter workspaceScopeFilter,
            GitHubInstallationRequests installationRequests,
            WebClient.Builder webClients) {
        this.installationRequests = installationRequests;
        this.gitHubAppTokenService = gitHubAppTokenService;
        this.githubLifecycleListener = githubLifecycleListener;
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.webClient = webClients
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
                    .block(WebClientConnectors.READ_TIMEOUT);

            if (appInfo == null) {
                log.warn("Skipped GitHub App processing: reason=nullAppInfoResponse");
                return;
            }

            log.info(
                    "Authenticated as GitHub App: appName={}, appSlug={}, appId={}",
                    appInfo.name(),
                    appInfo.slug(),
                    appInfo.id());

            List<InstallationDto> installations = webClient
                    .get()
                    .uri("/app/installations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .bodyToFlux(InstallationDto.class)
                    .collectList()
                    .block(WebClientConnectors.READ_TIMEOUT);

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
                log.info(
                        "Processed GitHub App installation: installationId={}, selection={}",
                        installation.id(),
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
                    githubLifecycleListener.suspendForInstallation(installation.id());
                }
            });
            return;
        }
        gitHubAppTokenService.markInstallationActive(installation.id());

        String login = installation.account().login();
        String accountType = installation.account().type();
        IntegrationLifecycleListener.AccountKind wsAccountKind = "Organization".equalsIgnoreCase(accountType)
                ? IntegrationLifecycleListener.AccountKind.ORGANIZATION
                : IntegrationLifecycleListener.AccountKind.USER;

        if (workspaceScopeFilter.isActive() && !workspaceScopeFilter.isOrganizationAllowed(login)) {
            log.info("Skipped installation sync: reason=filteredByScope, installationId={}", installation.id());
            installationRequests.recordFiltered(
                    installation.id(), installation.account().id(), login, wsAccountKind);
            return;
        }

        long installationId = installation.id();
        RepositorySelection selection = convertRepositorySelection(installation.repositorySelection());

        log.info("Ensured installation workspace: installationId={}, selection={}", installationId, selection);

        var account = installation.account();

        Workspace workspace = githubLifecycleListener.createOrUpdateFromInstallation(
                installationId, account.id(), login, wsAccountKind, account.avatarUrl(), selection);

        if (workspace == null) {
            log.info("Skipped installation sync: reason=noWorkspace, installationId={}", installationId);
            return;
        }

        workspace = workspaceService.updateAccountLogin(workspace.getId(), login);

        log.info("Configured organization sync via webhooks: workspaceId={}", workspace.getId());
    }

    private @Nullable RepositorySelection convertRepositorySelection(@Nullable String selection) {
        if (selection == null) {
            return null;
        }
        return switch (selection.toLowerCase(Locale.ROOT)) {
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
