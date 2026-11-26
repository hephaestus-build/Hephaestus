package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventOffered;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds the event ledger on every server. Retained evidence expires even where reviews do not run; only offering
 * due occasions for review again needs the agent.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Selects due item events for all workspaces; consumers retain each event's workspace")
@RequiredArgsConstructor
public class PendingItemEventReaper {
    private final ItemEventRepository repository;
    private final ItemEventLedgerProperties properties;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final Environment environment;

    @Scheduled(fixedDelay = 15, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "pending-item-event-reaper", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        repository.forgetExpiredEvidence(now, properties.sweepBatchSize());
        expire(now);
        repository.deleteUnreferenced(now.minus(properties.retainFor()), properties.sweepBatchSize());
        if (reviewsEnabled()) offerDue(now, properties.sweepBatchSize(), clock instanceof ControlledClock);
    }

    /** Offers the work due on the scenario clock without waiting for the wall-clock sweep. */
    @Transactional
    public void advance(Instant instant) {
        expire(instant);
        // Evaluation waits on the durable publication after the scenario clock moves this occasion's retry time.
        if (reviewsEnabled()) offerDue(instant, Integer.MAX_VALUE, true);
    }

    private void expire(Instant now) {
        repository.expire(
                now.minus(properties.pendingLapseAfter()), now, ReviewRequestReason.PENDING_DEADLINE_EXCEEDED);
    }

    private boolean reviewsEnabled() {
        return environment.getProperty(RuntimeRole.AGENT_ENABLED_PROPERTY, Boolean.class, false);
    }

    private void offerDue(Instant now, int limit, boolean durable) {
        var due = repository.claimDue(now, limit);
        for (var event : due) {
            repository.attempted(event.getWorkspaceId(), event.getId(), now, now.plus(properties.pendingRetryAfter()));
            var recorded = new ItemEventRecorded(event.getWorkspaceId(), event.getId(), event.getItemId());
            publisher.publishEvent(
                    durable
                            ? recorded
                            : new ItemEventOffered(recorded.workspaceId(), recorded.eventId(), recorded.itemId()));
        }
    }
}
