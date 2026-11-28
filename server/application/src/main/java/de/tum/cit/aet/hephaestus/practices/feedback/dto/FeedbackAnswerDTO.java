package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.feedback.response.CorrectionWindow;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A developer's answer to feedback, as a workspace admin reads it. The developer's rating never crosses, and only the
 * explanation of a disagreement does, as a correction for {@link CorrectionWindow#LENGTH} (ADR 0125); every other note
 * stays the developer's alone (ADR 0047). The developer reads their own full response as {@link FeedbackResponseDTO}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(
        description = "A developer's answer to feedback: the resolution, and the explanation only for a disagreement"
                + " that the developer gave in the last 30 days")
public record FeedbackAnswerDTO(
        @NonNull FeedbackResolution resolution,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Only for a disagreement, and only for 30 days after the developer gave it")
        @Nullable
        String comment,

        @NonNull Instant createdAt) {

    /**
     * {@code null} for a response without a resolution: a rating alone is no answer an admin may read.
     *
     * @param answeredAt when the developer gave this answer, which a later rating or private note does not move
     * @param now the time of the read, which decides whether the explanation is still a correction
     */
    public static @Nullable FeedbackAnswerDTO of(FeedbackResponse response, @Nullable Instant answeredAt, Instant now) {
        FeedbackResolution resolution = response.getResolution();
        if (resolution == null || answeredAt == null) {
            return null;
        }
        boolean correction = resolution == FeedbackResolution.DISAGREE && CorrectionWindow.open(answeredAt, now);
        return new FeedbackAnswerDTO(resolution, correction ? response.getComment() : null, answeredAt);
    }
}
