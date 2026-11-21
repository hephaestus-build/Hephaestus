package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptRunDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewPracticeDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceName,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Where the practice is edited now, which decides whether Practice setup can edit it")
        PracticeEditedIn editedIn,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$") @Nullable
        String practiceVersionId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Integer number,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewPracticeStatus status,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String reason,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewItemCountsDTO itemCounts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewResultCountsDTO results,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        PracticeScriptRunDTO script) {}
