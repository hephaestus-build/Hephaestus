package de.tum.cit.aet.hephaestus.activity;

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
        @Nullable String sourceKey) {}
