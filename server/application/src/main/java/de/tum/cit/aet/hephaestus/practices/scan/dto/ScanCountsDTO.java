package de.tum.cit.aet.hephaestus.practices.scan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record ScanCountsDTO(
        @Nullable Integer files,

        @Nullable Integer practiceItemPairs,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int reviewed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int reused,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int met,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int notMet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int doesNotApply,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int couldNotDetermine,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int problems,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int openProblems,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int withheld,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int notReviewed) {}
