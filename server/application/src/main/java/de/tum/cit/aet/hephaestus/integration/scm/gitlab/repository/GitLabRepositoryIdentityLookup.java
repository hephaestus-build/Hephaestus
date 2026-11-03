package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractNumericId;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryOriginNotSupportedException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabRepositoryIdentityLookup implements RepositoryIdentityLookup {
    private final GitLabProjectSyncService projects;
    private final ConnectionRepository connections;
    private final CredentialReader credentials;
    private final GitLabTokenService tokens;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    /**
     * The token and the server it is sent to come from one read of the workspace's active connection, before GitLab is
     * asked; neither is read again. Empty without an active GitLab connection that stores a token.
     *
     * @throws RepositoryOriginNotSupportedException before any token is read, when the server GitLab would be asked
     *     on is not the server this connection's repositories are recorded under
     */
    @Override
    public Optional<RepositoryIdentity> resolve(long workspaceId, String fullPath) {
        var connection =
                connections.findActive(workspaceId, IntegrationKind.GITLAB).orElse(null);
        if (connection == null || !(connection.getConfig() instanceof ConnectionConfig.GitLabConfig config)) {
            return Optional.empty();
        }
        String serverUrl = tokens.serverUrlOf(config.serverUrl());
        if (!(credentials.credentialsOf(connection).orElse(null) instanceof BearerToken bearer)
                || bearer.token().isBlank()) {
            return Optional.empty();
        }
        return projects.fetchProject(workspaceId, bearer.token(), serverUrl, fullPath)
                .flatMap(project -> {
                    String reportedPath = project.fullPath();
                    if (reportedPath == null) return Optional.empty();
                    return Optional.of(new RepositoryIdentity(extractNumericId(project.id()), reportedPath));
                });
    }
}
