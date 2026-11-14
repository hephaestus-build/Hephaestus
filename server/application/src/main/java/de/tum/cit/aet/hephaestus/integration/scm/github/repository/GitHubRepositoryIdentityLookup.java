package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.BootstrapRepositoryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads a repository's identity with a token-managed workspace's own token. An App installation grants its
 * repositories itself, so it is never asked here.
 */
@Component
@RequiredArgsConstructor
public class GitHubRepositoryIdentityLookup implements RepositoryIdentityLookup, BootstrapRepositoryLookup {
    private static final String QUERY_DOCUMENT = "GetRepository";

    /** The only origin the GraphQL client asks; a token for another origin is never sent through it. */
    private static final String CLIENT_ORIGIN = "https://github.com";

    private final GitHubGraphQlClientProvider clients;
    private final ConnectionService connections;
    private final GitHubSyncProperties syncProperties;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    /**
     * Empty unless GitHub names the repository to this workspace's token, with its id and current name, in a whole
     * and error-free answer. The token and its server come from one read of the workspace's active connection, before
     * GitHub is asked, and neither is read again; a token stored for another server is never read. Changes no mirror.
     */
    @Override
    public Optional<RepositoryIdentity> resolve(long workspaceId, String fullPath) {
        var connection = connections
                .findActiveBearerConnection(
                        workspaceId,
                        IntegrationKind.GITHUB,
                        config -> config instanceof ConnectionConfig.GitHubPatConfig pat && onClientOrigin(pat)
                                ? CLIENT_ORIGIN
                                : null)
                .orElse(null);
        if (connection == null || connection.bearer().token().isBlank()) return Optional.empty();
        return resolve(connection.bearer().token(), fullPath, workspaceId);
    }

    @Override
    public Optional<RepositoryIdentity> resolve(String token, String fullPath) {
        return resolve(token, fullPath, null);
    }

    private Optional<RepositoryIdentity> resolve(String token, String fullPath, @Nullable Long workspaceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Repository lookup requires no active database transaction");
        var name = GitHubRepositoryNameParser.parse(fullPath).orElse(null);
        if (name == null) return Optional.empty();
        clients.acquirePermission();
        ClientGraphQlResponse response = clients.withToken(token)
                .documentName(QUERY_DOCUMENT)
                .variable("owner", name.owner())
                .variable("name", name.name())
                .execute()
                .block(syncProperties.graphqlTimeout());
        // A refusal, a repository the token cannot see and a partial answer all confirm nothing.
        if (response == null || !response.isValid() || !response.getErrors().isEmpty()) return Optional.empty();
        if (workspaceId != null) clients.trackRateLimit(workspaceId, response);
        GHRepository repository = response.field("repository").toEntity(GHRepository.class);
        if (repository == null) return Optional.empty();
        var databaseId = repository.getDatabaseId();
        String reportedPath = repository.getNameWithOwner();
        if (databaseId == null || reportedPath == null) return Optional.empty();
        return Optional.of(new RepositoryIdentity(databaseId.longValue(), reportedPath));
    }

    private static boolean onClientOrigin(ConnectionConfig.GitHubPatConfig config) {
        return SourceProviderNamespaces.originOf(IntegrationKind.GITHUB, config)
                .filter(CLIENT_ORIGIN::equals)
                .isPresent();
    }
}
