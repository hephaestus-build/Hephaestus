package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.metrics.ActivityMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class ActivityFactDelivery {
    private final ApplicationEventPublisher publisher;
    private final ActivityEventService activity;
    private final ActivityPersonWriteAdmission admission;
    private final ActivityLedgerRepository ledger;
    private final WorkspaceActorSelector actors;
    private final UserRepository users;
    private final RepositoryRepository repositories;
    private final MeterRegistry meters;

    @Transactional(propagation = Propagation.MANDATORY)
    void record(
            Long workspaceId,
            ActivityEventType type,
            Instant occurredAt,
            @Nullable User actor,
            @Nullable Repository repository,
            ActivityTargetType targetType,
            Long targetId,
            @Nullable String sourceKey) {
        if (actor != null && !admission.hold(actor)) return;
        publisher.publishEvent(new ActivityFact(
                workspaceId,
                type,
                occurredAt,
                actor != null ? actor.getId() : null,
                repository != null ? repository.getId() : null,
                targetType,
                targetId,
                sourceKey));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordDeleted(
            Long workspaceId,
            ActivityEventType type,
            Instant occurredAt,
            ActivityTargetType targetType,
            Long targetId,
            @Nullable Long repositoryId) {
        publisher.publishEvent(
                new ActivityFact(workspaceId, type, occurredAt, null, repositoryId, targetType, targetId, null));
    }

    @Async("activityExecutor")
    @ApplicationModuleListener
    public void on(ActivityFact fact) {
        try {
            if (fact.repositoryId() != null) {
                var provider = actors.connectedProviderId(fact.workspaceId());
                if (provider.isEmpty()
                        || !ledger.isMonitored(fact.workspaceId(), fact.repositoryId(), provider.get())) {
                    meters.counter(ActivityMetrics.ACTIVITY_EVENTS_FAILED).increment();
                    return;
                }
            }
            var actor = fact.actorId() != null ? users.findById(fact.actorId()).orElse(null) : null;
            if (fact.actorId() != null && actor == null) {
                meters.counter(ActivityMetrics.ACTIVITY_EVENTS_FAILED).increment();
                return;
            }
            activity.record(
                    fact.workspaceId(),
                    fact.eventType(),
                    fact.occurredAt(),
                    actor,
                    fact.repositoryId() != null ? repositories.getReferenceById(fact.repositoryId()) : null,
                    fact.targetType(),
                    fact.targetId(),
                    fact.sourceKey());
        } catch (RuntimeException failure) {
            meters.counter(ActivityMetrics.ACTIVITY_EVENTS_FAILED).increment();
            throw failure;
        }
    }
}
