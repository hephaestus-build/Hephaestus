package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.metrics.ActivityMetrics;
import de.tum.cit.aet.hephaestus.activity.spi.ActivityRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records activity from authenticated provider events; performs no caller authorization.
 * Cross-module callers use {@link ActivityRecorder}, not controllers.
 */
@Slf4j
@Service
public class ActivityEventService implements ActivityRecorder {

    private final ActivityEventRepository eventRepository;
    private final WorkspaceRepository workspaceRepository;
    private final Counter eventsRecordedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsFailedCounter;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<ActivityEventType, Timer> eventTypeTimers = new ConcurrentHashMap<>();

    public ActivityEventService(
            ActivityEventRepository eventRepository,
            WorkspaceRepository workspaceRepository,
            MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.workspaceRepository = workspaceRepository;
        this.eventsRecordedCounter = Counter.builder(ActivityMetrics.ACTIVITY_EVENTS_RECORDED)
                .description("Number of activity events recorded")
                .register(meterRegistry);
        this.eventsDuplicateCounter = Counter.builder(ActivityMetrics.ACTIVITY_EVENTS_DUPLICATE)
                .description("Number of duplicate activity events skipped")
                .register(meterRegistry);
        this.eventsFailedCounter = Counter.builder(ActivityMetrics.ACTIVITY_EVENTS_FAILED)
                .description("Number of activity events that failed to record after retries")
                .register(meterRegistry);
        this.meterRegistry = meterRegistry;
    }

    private Timer getTimerForEventType(ActivityEventType eventType) {
        return eventTypeTimers.computeIfAbsent(
                eventType,
                type -> Timer.builder(ActivityMetrics.ACTIVITY_EVENTS_RECORD_DURATION_BY_TYPE)
                        .description("Time to persist activity event by type")
                        .tag("eventType", type.name())
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry));
    }

    /**
     * @return false for a duplicate event key or missing workspace; true when inserted
     */
    @Override
    @Transactional
    @Observed(name = "activity.record", contextualName = "record-activity-event")
    public boolean record(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            @Nullable User actor,
            @Nullable Repository repository,
            ActivityTargetType targetType,
            Long targetId) {
        return persist(workspaceId, eventType, occurredAt, actor, repository, targetType, targetId);
    }

    private boolean persist(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            @Nullable User actor,
            @Nullable Repository repository,
            ActivityTargetType targetType,
            Long targetId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            eventsFailedCounter.increment();
            log.warn(
                    "Failed to record event, workspace not found: scopeId={}, eventType={}, targetId={}",
                    workspaceId,
                    eventType,
                    targetId);
            return false;
        }

        String eventKey = ActivityEvent.buildKey(eventType, targetId, occurredAt);

        // ON CONFLICT handles concurrent deliveries without a check-then-insert race.
        Timer eventTimer = getTimerForEventType(eventType);
        long startTime = System.nanoTime();
        int rowsInserted = eventRepository.insertIfAbsent(
                UUID.randomUUID(),
                eventKey,
                eventType.name(),
                occurredAt,
                actor != null ? actor.getId() : null,
                workspaceId,
                repository != null ? repository.getId() : null,
                targetType.getValue(),
                targetId);
        eventTimer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

        if (rowsInserted == 0) {
            eventsDuplicateCounter.increment();
            log.debug("Skipped duplicate event: eventKey={}", eventKey);
            return false;
        }

        eventsRecordedCounter.increment();

        // Per-event details stay at DEBUG; metrics and sync rollups report volume.
        log.debug(
                "Recorded activity event: eventType={}, targetId={}, scopeId={}, actorId={}",
                eventType,
                targetId,
                workspaceId,
                actor != null ? actor.getId() : null);
        return true;
    }

    /** Records deletions without actor or repository context; the target may be gone. */
    @Override
    @Transactional
    @Observed(name = "activity.record.deleted", contextualName = "record-deleted-activity-event")
    public boolean recordDeleted(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            ActivityTargetType targetType,
            Long targetId) {
        return persist(workspaceId, eventType, occurredAt, null, null, targetType, targetId);
    }
}
