package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One question to Heph and the work to answer it")
public record MentorTurnDTO(
        @NonNull UUID id,
        @NonNull UUID conversationId,

        @Schema(description = "Set when the turn runs in a thread: the top-level message the thread replies to")
        @Nullable
        UUID parentEntryId,

        @Schema(description = "The submission that asked; the question's entry carries the same value") @NonNull
        String requestId,

        MentorTurn.@NonNull Status status,

        @Schema(
                description = "Attempts started so far; 0 while the turn waits for its first start. A lost lease"
                        + " queues the turn again with the same attempt, and the next start counts up. Thus, with the"
                        + " same attempt, RUNNING comes before QUEUED or an end.",
                requiredMode = RequiredMode.REQUIRED)
        int attempt,

        MentorTurn.@NonNull Surface surface,

        @Schema(description = "Heph's answer in progress; only while the turn runs") @Nullable
        MentorTurnPartialDTO partial,

        @Schema(description = "Why the turn failed, worded for the developer; only on a failed turn") @Nullable
        String error,

        @NonNull Instant createdAt,
        @Nullable Instant finishedAt) {}
