package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record TrialProblemDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Severity severity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String explanation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TrialCitationDTO> citations,
        @Nullable String nextStep) {}
