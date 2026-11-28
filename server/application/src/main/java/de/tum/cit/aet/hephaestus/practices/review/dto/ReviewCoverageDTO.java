package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeLevel;
import io.swagger.v3.oas.annotations.media.Schema;

/** The planned pairs of the practices of one level, by what became of them. A review never sums two levels. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewCoverageDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PracticeLevel level,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewItemCountsDTO counts) {}
