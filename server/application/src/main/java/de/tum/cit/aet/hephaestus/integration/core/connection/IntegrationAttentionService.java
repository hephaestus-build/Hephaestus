package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Each connection lookup includes its owning workspace")
public class IntegrationAttentionService {
    private final ConnectionRepository connections;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void report(
            long connectionId, long workspaceId, IntegrationAttentionChangedEvent.Problem problem, boolean recovered) {
        recordChange(connectionId, workspaceId, problem, recovered);
    }

    private void recordChange(
            long connectionId, long workspaceId, IntegrationAttentionChangedEvent.Problem problem, boolean recovered) {
        connections.acquireLifecycleLock(connectionId, workspaceId);
        connections.findByIdAndWorkspaceId(connectionId, workspaceId).ifPresent(connection -> {
            if (recovered ? connection.getAttentionProblem() != problem : connection.getAttentionProblem() == problem) {
                return;
            }
            connection.changeAttention(recovered ? null : problem);
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
