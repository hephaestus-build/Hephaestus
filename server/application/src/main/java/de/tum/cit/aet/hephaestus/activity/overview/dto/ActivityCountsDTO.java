package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

public record ActivityCountsDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) long contributions,
        @Schema(requiredMode = RequiredMode.REQUIRED) long pullRequestsOpened,
        @Schema(requiredMode = RequiredMode.REQUIRED) long pullRequestsMerged,
        @Schema(requiredMode = RequiredMode.REQUIRED) long pullRequestsReviewed,
        @Schema(requiredMode = RequiredMode.REQUIRED) long peopleHelped,
        @Schema(requiredMode = RequiredMode.REQUIRED) long issuesOpened,
        @Schema(requiredMode = RequiredMode.REQUIRED) long comments,
        @Schema(requiredMode = RequiredMode.REQUIRED) long activeWeeks) {}
