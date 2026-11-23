package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.core.event.ContentFree;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The activity write, without the provider event's work text. */
public record ActivityFact(
        Long workspaceId,
        ActivityEventType eventType,
        Instant occurredAt,
        @Nullable Long actorId,
        @Nullable Long repositoryId,
        ActivityTargetType targetType,
        Long targetId,
        @ContentFree @Nullable String sourceKey) {}
