package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace's one active GitHub or GitLab connection, read in one query. Hephaestus supports one SCM provider per
 * workspace, so two active SCM rows are corrupt data and every read fails loud.
 */
@Component
public class ActiveScmConnections {

    private static final List<IntegrationKind> SCM_KINDS = List.of(IntegrationKind.GITHUB, IntegrationKind.GITLAB);

    private final ConnectionRepository connections;
    private final CredentialReader credentials;

    public ActiveScmConnections(ConnectionRepository connections, CredentialReader credentials) {
        this.connections = connections;
        this.credentials = credentials;
    }

    @Transactional(readOnly = true)
    public Optional<IntegrationKind> kind(long workspaceId) {
        return active(workspaceId).map(Connection::getKind);
    }

    /** The connection's configuration, without its credential. */
    @Transactional(readOnly = true)
    public Optional<ConnectionConfig> config(long workspaceId) {
        return active(workspaceId).map(Connection::getConfig);
    }

    @Transactional(readOnly = true)
    public boolean isCurrent(long workspaceId, ActiveScm credential) {
        return activeForRequests(workspaceId)
                .filter(connection -> connection.getId() == credential.connectionId()
                        && connection.getCredentialsGeneration() == credential.credentialsGeneration())
                .isPresent();
    }

    /**
     * The connection with its personal access token. Only a GitHub PAT or GitLab connection has its credential read:
     * an App connection stores installation identity that nothing runs on. An unreadable credential is a recorded
     * state of the connection, so it leaves the caller's transaction usable.
     */
    @Transactional(
            readOnly = true,
            noRollbackFor = {CredentialUnreadableException.class, MissingCredentialKeyException.class})
    public Optional<ActiveScm> withToken(long workspaceId) {
        return activeForRequests(workspaceId).map(connection -> {
            boolean token = connection.getConfig() instanceof ConnectionConfig.GitHubPatConfig
                    || connection.getConfig() instanceof ConnectionConfig.GitLabConfig;
            BearerToken bearer = token
                    ? credentials
                            .credentialsOf(connection)
                            .flatMap(b -> b instanceof BearerToken read ? Optional.of(read) : Optional.empty())
                            .orElse(null)
                    : null;
            return new ActiveScm(
                    connection.getId(),
                    connection.getCredentialsGeneration(),
                    connection.getKind(),
                    connection.getConfig(),
                    bearer);
        });
    }

    private Optional<Connection> activeForRequests(long workspaceId) {
        return oneActive(connections.findActiveForRequests(workspaceId, SCM_KINDS));
    }

    private Optional<Connection> active(long workspaceId) {
        return oneActive(
                connections.findByWorkspaceIdAndStateAndKindIn(workspaceId, IntegrationState.ACTIVE, SCM_KINDS));
    }

    private static Optional<Connection> oneActive(List<Connection> active) {
        if (active.size() > 1) {
            throw new IllegalStateException(
                    "This workspace has active GitHub and GitLab connections, which Hephaestus does not support. Ask your instance operator to fix it.");
        }
        return active.stream().findFirst();
    }

    /** The configuration of the active SCM connection and its personal access token, if any. */
    public record ActiveScm(
            long connectionId,
            long credentialsGeneration,
            IntegrationKind kind,
            ConnectionConfig config,
            @Nullable BearerToken bearer) {}
}
