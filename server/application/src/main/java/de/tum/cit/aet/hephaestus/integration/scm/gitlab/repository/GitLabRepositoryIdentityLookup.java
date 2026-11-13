package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractNumericId;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
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
    private final ConnectionService connections;
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
        var connection = connections
                .findActiveBearerConnection(
                        workspaceId,
                        IntegrationKind.GITLAB,
                        config -> config instanceof ConnectionConfig.GitLabConfig gitLab
                                ? tokens.serverUrlOf(gitLab.serverUrl())
                                : null)
                .orElse(null);
        if (connection == null || connection.bearer().token().isBlank()) return Optional.empty();
        var bearer = connection.bearer();
        String serverUrl = connection.serverUrl();
        return projects.fetchProject(workspaceId, bearer.token(), serverUrl, fullPath)
                .flatMap(project -> {
                    String reportedPath = project.fullPath();
                    if (reportedPath == null) return Optional.empty();
                    return Optional.of(new RepositoryIdentity(extractNumericId(project.id()), reportedPath));
                });
    }
}
