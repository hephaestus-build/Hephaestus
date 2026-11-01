package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ConsumerDeadLetterDTO(
        @NonNull Long id,
        @NonNull String stream,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sequence,
        @NonNull String errorClass,
        @NonNull Instant receivedAt,
        @NonNull Instant failedAt,
        @Nullable Instant replayedAt,
        @Nullable Long replayedBy) {
    static ConsumerDeadLetterDTO from(ConsumerDeadLetter row) {
        return new ConsumerDeadLetterDTO(
                row.getId(),
                row.getStreamName(),
                row.getStreamSequence(),
                row.getErrorClass(),
                row.getReceivedAt(),
                row.getFailedAt(),
                row.getReplayedAt(),
                row.getReplayedBy());
    }
}
