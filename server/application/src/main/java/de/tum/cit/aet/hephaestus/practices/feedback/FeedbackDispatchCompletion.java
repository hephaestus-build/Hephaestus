package de.tum.cit.aet.hephaestus.practices.feedback;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FeedbackDispatchCompletion(
        UUID id,
        Long workspaceId,
        String owner,
        String state,
        @Nullable String externalRef,
        @Nullable String externalUrl,
        @Nullable String error,
        @Nullable String suppressionReason,
        String deliveredPlacements,
        Instant nextAttemptAt) {}
