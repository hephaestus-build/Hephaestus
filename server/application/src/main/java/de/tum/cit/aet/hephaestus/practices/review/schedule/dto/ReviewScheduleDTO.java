package de.tum.cit.aet.hephaestus.practices.review.schedule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCadence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewScheduleDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String itemKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewCadence cadence,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Integer lookbackDays,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String budgetUsd,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant nextRunAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant lastRunAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        AccountRefDTO createdBy,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        UUID setupId) {}
