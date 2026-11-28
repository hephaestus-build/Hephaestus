package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A developer's current disagreement with feedback, as a workspace admin reads it on a result. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FeedbackDisagreementDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID feedbackId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackDestination destination,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The developer's explanation, only for 30 days after they gave it (ADR 0125)")
        @Nullable
        String comment,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant respondedAt) {}
