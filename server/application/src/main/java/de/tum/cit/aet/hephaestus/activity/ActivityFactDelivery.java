package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.metrics.ActivityMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ActivityScmFacts;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ActivityFactDelivery {
    private final ApplicationEventPublisher publisher;
    private final ActivityEventService activity;
    private final ActivityPersonWriteAdmission admission;
    private final ActivityLedgerRepository ledger;
    private final WorkspaceActorSelector actors;
    private final ActivityScmFacts scm;
    private final MeterRegistry meters;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(ActivityFact fact) {
        if (fact.actorId() != null && !admission.holdActor(fact.actorId())) return;
        publisher.publishEvent(fact);
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
            var actor = fact.actorId() != null ? scm.user(fact.actorId()).orElse(null) : null;
            if (fact.actorId() != null && actor == null) {
                meters.counter(ActivityMetrics.ACTIVITY_EVENTS_FAILED).increment();
                return;
            }
            activity.record(
                    fact.workspaceId(),
                    fact.eventType(),
                    fact.occurredAt(),
                    actor,
                    fact.repositoryId() != null ? scm.repositoryReference(fact.repositoryId()) : null,
                    fact.targetType(),
                    fact.targetId(),
                    fact.sourceKey());
        } catch (RuntimeException failure) {
            meters.counter(ActivityMetrics.ACTIVITY_EVENTS_FAILED).increment();
            throw failure;
        }
    }
}
