package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService.InstallationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** One credential-selection path for GitHub GraphQL and read-only REST calls. */
@Service
@RequiredArgsConstructor
public class GitHubTokenService {
    private final InstallationTokenProvider tokenProvider;
    private final GitHubAppTokenService appTokens;

    public String getAccessToken(Long scopeId) {
        // Fail fast for suspended/inactive scopes - don't waste API calls
        if (!tokenProvider.isScopeActive(scopeId)) {
            throw new IllegalStateException(
                    "Scope " + scopeId + " is not active (suspended or purged). Refusing to mint token.");
        }

        AuthMode authMode = tokenProvider.getAuthMode(scopeId);

        if (authMode == AuthMode.INSTALLATION_APP) {
            Long installationId = tokenProvider
                    .getInstallationId(scopeId)
                    .orElseThrow(() -> new IllegalStateException("Scope " + scopeId + " has no installation id."));
            InstallationToken token = appTokens.getInstallationTokenDetails(installationId);
            return token.token();
        }

        return tokenProvider
                .getPersonalAccessToken(scopeId)
                .filter(t -> !t.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Scope " + scopeId + " is configured for PAT access but no token is stored."));
    }
}
