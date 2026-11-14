package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Connection access and audit writes that stay in the consuming use case's transaction. */
@Service
@RequiredArgsConstructor
public class ConnectionCommands {
    private final ConnectionRepository connectionRepository;
    private final ConnectionAuditRepository auditRepository;
    private final ConnectionService connections;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    /** Holds the same lifecycle lock in the caller's transaction and refreshes the aggregate it guards. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Connection> holdActiveConnection(long workspaceId, IntegrationKind kind) {
        return holdActiveConnection(workspaceId, kind, null);
    }

    /** Refuses a connection other than the one that answered a provider read. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Connection> holdAnsweringConnection(
            long workspaceId, IntegrationKind kind, long answeringConnectionId) {
        return holdActiveConnection(workspaceId, kind, answeringConnectionId);
    }

    private Optional<Connection> holdActiveConnection(
            long workspaceId, IntegrationKind kind, @Nullable Long expectedId) {
        if (connectionRepository.lockActiveConfig(workspaceId, kind).isEmpty()) return Optional.empty();
        return connectionRepository
                .findActive(workspaceId, kind)
                .filter(connection -> expectedId == null || Objects.equals(connection.getId(), expectedId))
                .map(connection -> {
                    Objects.requireNonNull(entityManager).refresh(connection);
                    return connection;
                })
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE);
    }

    /** The caller holds the workspace row first; even a no-op event invalidates an in-flight catalog. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fenceActiveInstallation(long workspaceId, long installationId) {
        connectionRepository.findActiveInstallationForUpdateAndIncrementVersion(
                workspaceId, Long.toString(installationId));
    }

    /** Holds the scoped connection row before an existing use case revalidates its captured authority. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Connection> holdConnection(long workspaceId, long connectionId) {
        if (connectionRepository
                .findAndLockLifecycleConnection(connectionId, workspaceId)
                .isEmpty()) return Optional.empty();
        return connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId);
    }

    /** Holds the scoped lifecycle row for the rest of the caller's write transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdLifecycleLock(long workspaceId, long connectionId) {
        connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
    }

    /** The scoped aggregate used by an existing write after its lifecycle lock is held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Connection> findHeldConnection(long workspaceId, long connectionId) {
        return connections.findInWorkspace(workspaceId, connectionId);
    }

    /** Admission reads the database configuration, rather than a cached aggregate, under its lifecycle lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ConnectionConfig> configurationInState(
            long workspaceId, long connectionId, IntegrationState state) {
        return connectionRepository.findConfigInState(connectionId, workspaceId, state);
    }

    /** Persists the replacement beside the provider cleanup it records, under the caller's held lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceHeldConfig(Connection connection, ConnectionConfig replacement) {
        connection.setConfig(replacement);
        connectionRepository.save(connection);
    }

    /** Records an administrative change beside the write, in the transaction that holds the connection. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAdministrativeChange(
            Connection connection, String eventType, long actorAccountId, String correlationId, String detail) {
        var audit = new ConnectionAudit(
                connection,
                eventType,
                connection.getState(),
                connection.getState(),
                "ADMIN",
                null,
                correlationId,
                detail);
        audit.setActorAccountId(actorAccountId);
        auditRepository.save(audit);
    }
}
