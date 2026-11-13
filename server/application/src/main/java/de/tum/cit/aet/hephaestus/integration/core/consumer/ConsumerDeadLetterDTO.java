package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A provider event that failed for good: its provider, why, and whether someone replayed it. */
public record ConsumerDeadLetterDTO(
        @NonNull Long id,
        @NonNull String provider,
        @NonNull String errorClass,
        @NonNull Instant receivedAt,
        @NonNull Instant failedAt,
        @Nullable Instant replayedAt,
        @Nullable Long replayedBy) {
    static ConsumerDeadLetterDTO from(ProviderEventStore.DeadLetter row) {
        return new ConsumerDeadLetterDTO(
                row.id(),
                row.provider(),
                row.errorClass(),
                row.receivedAt(),
                row.failedAt(),
                row.replayedAt(),
                row.replayedBy());
    }
}
