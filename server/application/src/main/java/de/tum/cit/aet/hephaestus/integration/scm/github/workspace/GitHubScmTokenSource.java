package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ScmTokenSource} for GitHub — exposes either a minted GitHub App installation
 * token or a stored PAT for the workspace's active GitHub connection.
 *
 * <p>Resolution precedence is GitHub-App-first (installation tokens are short-lived but
 * fully-scoped); a PAT is only consulted when no active App config exists. The returned
 * server URL is whatever the active connection's config carries, defaulting to
 * {@code https://github.com} when blank.
 */
@Component
public class GitHubScmTokenSource implements ScmTokenSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubScmTokenSource.class);
    private static final String DEFAULT_GITHUB_URL = "https://github.com";

    private final ConnectionService connectionService;
    private final GitHubAppTokenService gitHubAppTokenService;

    public GitHubScmTokenSource(ConnectionService connectionService, GitHubAppTokenService gitHubAppTokenService) {
        this.connectionService = connectionService;
        this.gitHubAppTokenService = gitHubAppTokenService;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public Optional<String> accessToken(long scopeId) {
        // GitHub App first: mint a short-lived installation token.
        Optional<ConnectionConfig.GitHubAppConfig> appConfig = connectionService.findActiveGitHubAppConfig(scopeId);
        if (appConfig.isPresent()) {
            Long installationId = appConfig.get().installationId();
            if (installationId == null) {
                return Optional.empty();
            }
            try {
                String token = gitHubAppTokenService.getOrRefreshToken(installationId);
                return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(token);
            } catch (Exception e) {
                log.warn(
                        "Failed to mint GitHub App installation token: scopeId={}, installationId={}, error={}",
                        scopeId,
                        appConfig.get().installationId(),
                        e.getMessage());
                return Optional.empty();
            }
        }
        // PAT fallback.
        return connectionService
                .findActiveBearerToken(scopeId, IntegrationKind.GITHUB)
                .map(b -> b.token())
                .filter(t -> t != null && !t.isBlank());
    }

    @Override
    public Optional<String> serverUrl(long scopeId) {
        return connectionService
                .findActive(scopeId, IntegrationKind.GITHUB)
                .map(c -> {
                    if (c.getConfig() instanceof ConnectionConfig.GitHubAppConfig app) {
                        return app.serverUrl();
                    }
                    if (c.getConfig() instanceof ConnectionConfig.GitHubPatConfig pat) {
                        return pat.serverUrl();
                    }
                    return null;
                })
                .filter(u -> u != null && !u.isBlank())
                .or(() -> Optional.of(DEFAULT_GITHUB_URL));
    }

    @Override
    public Optional<String> reviewHeadRef(long pullRequestNumber) {
        return pullRequestNumber > 0 ? Optional.of("refs/pull/" + pullRequestNumber + "/head") : Optional.empty();
    }

    @Override
    public boolean recordsReviewDiffBase() {
        return false;
    }
}
