package de.tum.cit.aet.hephaestus.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One conversation turn: a requested answer or an opted-in proactive digest")
public record ConversationTurnDTO(
        @NonNull UUID id,
        @NonNull UUID conversationId,

        @Schema(description = "Set when the turn runs in a thread: the top-level message the thread replies to")
        @Nullable
        UUID parentEntryId,

        @Schema(description = "The submission or schedule key; the stimulus entry carries the same value") @NonNull
        String requestId,

        ConversationTurn.@NonNull Status status,

        @Schema(
                description =
                        "Why a queued turn has not started. CAPACITY: a server may start it, and every conversation"
                                + " slot of the deployment is taken, so it starts when one is free. Absent when the turn does"
                                + " not wait, waits for an earlier question of its conversation, or waits for a reason that"
                                + " the server does not know, such as a server without a configured conversation turn capacity.")
        ConversationTurn.@Nullable WaitingFor waitingFor,

        @Schema(
                description = "Attempts started so far; 0 while the turn waits for its first start. A lost lease"
                        + " queues the turn again with the same attempt, and the next start counts up. Thus, with the"
                        + " same attempt, RUNNING comes before QUEUED or an end.",
                requiredMode = RequiredMode.REQUIRED)
        int attempt,

        ConversationTurn.@NonNull Surface surface,

        @Schema(
                description =
                        "Where the running attempt stands. STARTING: it prepares, and the server admitted none of its"
                                + " model calls yet. WORKING: the server admitted a model call of the attempt. Only while the"
                                + " turn runs.")
        ConversationTurn.@Nullable Phase phase,

        @Schema(
                description = "The attempts the turn may take: its first and its retries after a lost holder",
                requiredMode = RequiredMode.REQUIRED)
        int maxAttempts,

        @Schema(description = "Hephaestus's answer in progress; only while the turn runs") @Nullable
        ConversationTurnPartialDTO partial,

        @Schema(description = "Why the turn failed, worded for the developer; only on a failed turn") @Nullable
        String error,

        @Schema(description = "Why the turn failed, for a client to act on; only on a failed turn")
        ConversationTurn.@Nullable FailureReason failureReason,

        @NonNull Instant createdAt,
        @Nullable Instant finishedAt) {}
