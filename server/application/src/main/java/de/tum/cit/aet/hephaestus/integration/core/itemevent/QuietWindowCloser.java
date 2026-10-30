package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers each item whose quiet window closed for its readiness edge. The edge decides every occasion of the window; if
 * it fails, the window is offered again one window later, not on the next tick.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(name = RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Selects closed windows for all workspaces; consumers retain each event's workspace")
@RequiredArgsConstructor
public class QuietWindowCloser {
    private final ItemEventRepository repository;
    private final ItemEventLedgerProperties properties;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "quiet-window-closer", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Transactional
    public void close() {
        Instant now = clock.instant();
        for (var event : repository.findClosedWindows(now, properties.sweepBatchSize())) {
            repository.postponeWindow(event.getWorkspaceId(), event.getItemId(), now.plus(properties.quietWindow()));
            publisher.publishEvent(new ItemEventRecorded(event.getWorkspaceId(), event.getId(), event.getItemId()));
        }
    }
}
