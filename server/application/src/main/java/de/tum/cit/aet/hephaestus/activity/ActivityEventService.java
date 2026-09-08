package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.metrics.ActivityMetrics;
import de.tum.cit.aet.hephaestus.activity.scoring.ExperiencePointProperties;
import de.tum.cit.aet.hephaestus.activity.scoring.XpPrecision;
import de.tum.cit.aet.hephaestus.activity.spi.ActivityRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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
    private final ExperiencePointProperties xpProperties;
    private final Counter eventsRecordedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsFailedCounter;
    private final DistributionSummary xpDistribution;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<ActivityEventType, Timer> eventTypeTimers = new ConcurrentHashMap<>();

    public ActivityEventService(
            ActivityEventRepository eventRepository,
            WorkspaceRepository workspaceRepository,
            ExperiencePointProperties xpProperties,
            MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.workspaceRepository = workspaceRepository;
        this.xpProperties = xpProperties;
        this.eventsRecordedCounter = Counter.builder(ActivityMetrics.ACTIVITY_EVENTS_RECORDED)
                .description("Number of activity events recorded")
                .register(meterRegistry);
        this.eventsDuplicateCounter = Counter.builder(ActivityMetrics.ACTIVITY_EVENTS_DUPLICATE)
                .description("Number of duplicate activity events skipped")
                .register(meterRegistry);
        this.eventsFailedCounter = Counter.builder(ActivityMetrics.ACTIVITY_EVENTS_FAILED)
                .description("Number of activity events that failed to record after retries")
                .register(meterRegistry);
        this.xpDistribution = DistributionSummary.builder(ActivityMetrics.ACTIVITY_XP_DISTRIBUTION)
                .description("Distribution of XP values recorded")
                .publishPercentiles(0.5, 0.95, 0.99)
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
            Long targetId,
            double xp) {
        return persist(workspaceId, eventType, occurredAt, actor, repository, targetType, targetId, xp);
    }

    private boolean persist(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            @Nullable User actor,
            @Nullable Repository repository,
            ActivityTargetType targetType,
            Long targetId,
            double xp) {
        if (!workspaceRepository.existsById(workspaceId)) {
            eventsFailedCounter.increment();
            log.warn(
                    "Failed to record event, workspace not found: scopeId={}, eventType={}, targetId={}",
                    workspaceId,
                    eventType,
                    targetId);
            return false;
        }

        double maxXp = xpProperties.maxXpPerEvent();
        double clampedXp = Math.max(0.0, Math.min(xp, maxXp));
        if (clampedXp != xp) {
            log.debug("Clamped XP value: originalXp={}, clampedXp={}, eventType={}", xp, clampedXp, eventType);
        }

        double roundedXp = XpPrecision.round(clampedXp);

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
                targetId,
                roundedXp);
        eventTimer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

        if (rowsInserted == 0) {
            eventsDuplicateCounter.increment();
            log.debug("Skipped duplicate event: eventKey={}", eventKey);
            return false;
        }

        eventsRecordedCounter.increment();
        xpDistribution.record(roundedXp);

        // Per-event details stay at DEBUG; metrics and sync rollups report volume.
        log.debug(
                "Recorded activity event: eventType={}, targetId={}, xp={}, scopeId={}, actorId={}",
                eventType,
                targetId,
                roundedXp,
                workspaceId,
                actor != null ? actor.getId() : null);
        return true;
    }

    /** Records deletions without actor/repository context or XP; the target may no longer exist. */
    @Override
    @Transactional
    @Observed(name = "activity.record.deleted", contextualName = "record-deleted-activity-event")
    public boolean recordDeleted(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            ActivityTargetType targetType,
            Long targetId) {
        return persist(workspaceId, eventType, occurredAt, null, null, targetType, targetId, 0.0);
    }
}
