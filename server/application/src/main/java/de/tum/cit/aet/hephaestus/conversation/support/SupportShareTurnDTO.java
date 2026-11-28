package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What an operator needs to find one turn in the server's logs and metrics, without its text: ids, states, times and
 * the closed kind of a failure.
 */
@Schema(description = "The diagnostics of one conversation turn. It holds no text.")
public record SupportShareTurnDTO(
        @NonNull UUID turnId,

        @Schema(description = "The run that executed the turn: the key of its server logs") @NonNull
        UUID runId,

        @NonNull String workspaceSlug,
        ConversationTurn.@NonNull Surface surface,
        ConversationTurn.@NonNull Status status,
        @Schema(requiredMode = RequiredMode.REQUIRED) int attempt,

        @Schema(description = "Why the turn failed, as a closed kind. Absent unless the turn failed.")
        ConversationTurn.@Nullable FailureReason failureReason,

        @Schema(description = "The kind of the last model call that failed, as the LLM proxy names it") @Nullable
        String failedCall,

        @Schema(description = "The status that the provider answered the failed call with") @Nullable
        Integer failedCallStatus,

        @NonNull Instant queuedAt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt) {

    static SupportShareTurnDTO of(ConversationTurn turn, String workspaceSlug) {
        return new SupportShareTurnDTO(
                turn.getId(),
                turn.getRunId(),
                workspaceSlug,
                turn.getSurface(),
                turn.getStatus(),
                turn.getAttempt(),
                turn.getStatus() == ConversationTurn.Status.FAILED
                        ? ConversationTurn.FailureReason.of(turn.getFailureCause())
                        : null,
                turn.getFailedCall(),
                turn.getFailedCallStatus(),
                turn.getQueuedAt(),
                turn.getStartedAt(),
                turn.getFinishedAt());
    }
}
