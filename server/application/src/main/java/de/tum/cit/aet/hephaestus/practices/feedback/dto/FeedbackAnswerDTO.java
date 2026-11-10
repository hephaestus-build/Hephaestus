package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A developer's answer to feedback, as a workspace admin reads it. The developer's rating never crosses, and only the
 * explanation of a disagreement does; every other note stays the developer's alone (ADR 0047). The developer reads
 * their own full response as {@link FeedbackResponseDTO}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A developer's answer to feedback: the resolution, and the explanation only for a disagreement")
public record FeedbackAnswerDTO(
        @NonNull FeedbackResolution resolution,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Only for a disagreement") @Nullable
        String comment,

        @NonNull Instant createdAt) {

    /** {@code null} for a response without a resolution: a rating alone is no answer an admin may read. */
    public static @Nullable FeedbackAnswerDTO of(FeedbackResponse response) {
        FeedbackResolution resolution = response.getResolution();
        if (resolution == null) {
            return null;
        }
        return new FeedbackAnswerDTO(
                resolution,
                resolution == FeedbackResolution.DISAGREE ? response.getComment() : null,
                response.getCreatedAt());
    }
}
