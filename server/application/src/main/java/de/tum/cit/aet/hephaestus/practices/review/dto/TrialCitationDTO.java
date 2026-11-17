package de.tum.cit.aet.hephaestus.practices.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where a trial's candidate cites the work, and the quoted text: text from the reviewed work. */
public record TrialCitationDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String path,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int startLine,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int endLine,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String quote) {}
