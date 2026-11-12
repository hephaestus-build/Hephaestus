package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewUsageDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String costUsd,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean priced,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether costUsd counts the upper bound of a call whose usage was not measured, so the"
                        + " review cost at most costUsd")
        boolean includesUpperBound,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewUsageAttemptDTO> attempts) {
    public ReviewUsageDTO {
        attempts = List.copyOf(attempts);
    }
}
