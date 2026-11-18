package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** What a trial's practice found, as admission accepted it. No observation records it. */
public record TrialOutcomeDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResultOutcome outcome,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String explanation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TrialProblemDTO> problems,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TrialCitationDTO> citations) {}
