package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record FeedbackResponseDTO(
        @Nullable FeedbackResolution resolution,

        @Nullable FeedbackUsefulness usefulness,

        @Nullable String comment,

        @Nullable Instant createdAt) {

    public static FeedbackResponseDTO from(FeedbackResponse response) {
        return new FeedbackResponseDTO(
                response.getResolution(), response.getUsefulness(), response.getComment(), response.getCreatedAt());
    }

    public static FeedbackResponseDTO empty() {
        return new FeedbackResponseDTO(null, null, null, null);
    }
}
