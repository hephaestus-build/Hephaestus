package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

public record ActivitySparklineWeekDTO(
        @NonNull Instant start,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long contributions) {}
