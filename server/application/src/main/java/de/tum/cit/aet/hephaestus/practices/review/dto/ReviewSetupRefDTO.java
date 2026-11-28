package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The review setup that started a review, as the review keeps a copy of it (ADR 0165 decision 10). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewSetupRefDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}
