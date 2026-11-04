package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@WorkspaceAgnostic("Each connection lookup includes its owning workspace")
public class IntegrationAttentionService {
    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ConnectionRepository connections;
    private final ConnectionService connectionService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void report(
            long connectionId, long workspaceId, IntegrationAttentionChangedEvent.Problem problem, boolean recovered) {
        recordChange(connectionId, workspaceId, problem, recovered);
    }

    /**
     * Records whether the provider accepted the stored token that made a call. The observation is scoped to that
     * token, not just its workspace: an old in-flight request must not degrade (or recover) a replacement token.
     */
    @Transactional
    public void observeCredential(IntegrationKind kind, long workspaceId, String token, boolean refused) {
        connectionService.findActive(workspaceId, kind).ifPresent(connection -> {
            if (refused
                    ? connection.getAttentionProblem() == IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED
                    : connection.getAttentionProblem() != IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED) {
                return;
            }
            connections.acquireLifecycleLock(connection.getId(), workspaceId);
            Objects.requireNonNull(entityManager).refresh(connection);
            if (connectionService
                    .findActiveBearerToken(workspaceId, kind)
                    .filter(current -> current.token().equals(token))
                    .isEmpty()) {
                return;
            }
            recordChange(
                    connection.getId(),
                    workspaceId,
                    IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED,
                    !refused);
        });
    }

    private void recordChange(
            long connectionId, long workspaceId, IntegrationAttentionChangedEvent.Problem problem, boolean recovered) {
        connections.acquireLifecycleLock(connectionId, workspaceId);
        connections.findByIdAndWorkspaceId(connectionId, workspaceId).ifPresent(connection -> {
            if (recovered ? connection.getAttentionProblem() != problem : connection.getAttentionProblem() == problem) {
                return;
            }
            connection.changeAttention(recovered ? null : problem);
            if (problem == IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED) {
                if (recovered) {
                    log.info(
                            "Integration credential accepted again: workspaceId={}, connectionId={}, kind={}",
                            workspaceId,
                            connectionId,
                            connection.getKind());
                } else {
                    log.warn(
                            "Integration credential refused, its sync waits for a reconnect: workspaceId={},"
                                    + " connectionId={}, kind={}",
                            workspaceId,
                            connectionId,
                            connection.getKind());
                }
            }
            events.publishEvent(new IntegrationAttentionChangedEvent(
                    connectionId,
                    workspaceId,
                    connection.getKind(),
                    problem,
                    recovered,
                    connection.getAttentionRevision(),
                    clock.instant()));
        });
    }

    // This state change must join activation before notification fan-out registers its before-commit callback.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onActivated(ConnectionLifecycleEvent.Activated event) {
        connections
                .findByIdAndWorkspaceId(event.connectionId(), event.workspaceId())
                .ifPresent(connection -> {
                    var problem = connection.getAttentionProblem();
                    if (problem != null) {
                        recordChange(event.connectionId(), event.workspaceId(), problem, true);
                    }
                });
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCredentialsReplaced(ConnectionCredentialsReplacedEvent event) {
        connections
                .findByIdAndWorkspaceId(event.connectionId(), event.workspaceId())
                .ifPresent(connection -> {
                    var problem = connection.getAttentionProblem();
                    if (problem == IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED
                            || problem == IntegrationAttentionChangedEvent.Problem.CREDENTIAL_EXPIRING) {
                        recordChange(event.connectionId(), event.workspaceId(), problem, true);
                    }
                });
    }

    @Transactional(readOnly = true)
    public boolean isCurrent(IntegrationAttentionChangedEvent event) {
        return connections
                .findByIdAndWorkspaceId(event.connectionId(), event.workspaceId())
                .filter(connection -> connection.getAttentionRevision() == event.revision())
                .filter(connection -> event.recovered()
                        ? connection.getAttentionProblem() == null
                        : connection.getAttentionProblem() == event.problem())
                .isPresent();
    }
}
