package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "What the practice's current version judges (ADR 0130). Two versions with one key and one"
                + " comparability class judge the same thing, in any workspace.")
public record PracticeIdentityDTO(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The domain-qualified key: <domain>/<key> from the catalog, or"
                        + " local/<workspace>/<practice> for a practice of this workspace")
        String key,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The comparability class. An edit that changes the judgement starts a new class")
        String comparabilityClass) {}
