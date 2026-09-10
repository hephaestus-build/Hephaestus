package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter that exposes the {@link InstallationTokenProvider} SPI on top of the
 * Connection registry. Every accessor pulls live state from the
 * {@link ConnectionService}; the {@link WorkspaceRepository} stays in the constructor
 * for one query — workspace lifecycle status — which the registry doesn't replicate.
 */
@Component
public class WorkspaceInstallationTokenProvider implements InstallationTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInstallationTokenProvider.class);

    private final WorkspaceRepository workspaceRepository;
    private final ConnectionService connectionService;

    public WorkspaceInstallationTokenProvider(
            WorkspaceRepository workspaceRepository, ConnectionService connectionService) {
        this.workspaceRepository = workspaceRepository;
        this.connectionService = connectionService;
    }

    @Override
    public Optional<Long> getInstallationId(Long scopeId) {
        return connectionService
                .findActiveGitHubAppConfig(scopeId)
                .map(ConnectionConfig.GitHubAppConfig::installationId);
    }

    @Override
    public Optional<String> getPersonalAccessToken(Long scopeId) {
        // Prefer GITHUB (PAT mode) → GITLAB. An App workspace's stored blob is installation identity
        // that nothing runs on, so it is not read here: the caller must use the App-token mint path.
        Optional<BearerToken> github =
                connectionService.findActiveGitHubPatConfig(scopeId).isPresent()
                        ? connectionService.findActiveBearerToken(scopeId, IntegrationKind.GITHUB)
                        : Optional.empty();
        return github.or(() -> connectionService.findActiveBearerToken(scopeId, IntegrationKind.GITLAB))
                .map(b -> b.token())
                .filter(token -> token != null && !token.isBlank());
    }

    @Override
    public AuthMode getAuthMode(Long scopeId) {
        var kind = connectionService.findActiveProviderKind(scopeId);
        if (kind.isEmpty()) {
            log.warn("Defaulted to INSTALLATION_APP auth mode: reason=noActiveSCMConnection, scopeId={}", scopeId);
            return AuthMode.INSTALLATION_APP;
        }
        return switch (kind.get()) {
            case GITHUB ->
                connectionService.findActiveGitHubAppConfig(scopeId).isPresent()
                        ? AuthMode.INSTALLATION_APP
                        : AuthMode.PERSONAL_ACCESS_TOKEN;
            case GITLAB -> AuthMode.PERSONAL_ACCESS_TOKEN;
            case SLACK, OUTLINE, KEYCLOAK_DIRECTORY -> {
                log.warn("Non-SCM provider for scope {}: kind={}; defaulting to INSTALLATION_APP", scopeId, kind.get());
                yield AuthMode.INSTALLATION_APP;
            }
        };
    }

    @Override
    public boolean isScopeActive(Long scopeId) {
        return workspaceRepository
                .findById(scopeId)
                .map(ws -> ws.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .orElse(false);
    }

    @Override
    public Optional<String> getServerUrl(Long scopeId) {
        return connectionService
                .findActiveGitLabConfig(scopeId)
                .map(ConnectionConfig.GitLabConfig::serverUrl)
                .or(() -> connectionService
                        .findActiveGitHubAppConfig(scopeId)
                        .map(ConnectionConfig.GitHubAppConfig::serverUrl))
                .or(() -> connectionService
                        .findActiveGitHubPatConfig(scopeId)
                        .map(ConnectionConfig.GitHubPatConfig::serverUrl))
                .filter(url -> url != null && !url.isBlank());
    }
}
