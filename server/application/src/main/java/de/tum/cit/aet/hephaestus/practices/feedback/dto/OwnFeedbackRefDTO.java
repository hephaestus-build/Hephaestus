package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record OwnFeedbackRefDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackDestination destination,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackState state,

        @Nullable Instant deliveredAt,

        @Nullable FeedbackResponseDTO response,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OwnFeedbackCopyRefDTO> copies) {
    public OwnFeedbackRefDTO {
        copies = List.copyOf(copies);
    }
}
