package de.tum.cit.aet.hephaestus.practices.feedback.page.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticePageEvidenceDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID resultId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String summary,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "What the review made of the practice on this work, so the timeline can draw it")
        ResultOutcome outcome) {}
