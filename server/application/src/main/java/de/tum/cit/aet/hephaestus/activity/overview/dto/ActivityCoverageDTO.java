package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Completion comes from backfill checkpoints, not the presence of ledger events. */
public record ActivityCoverageDTO(
        @Nullable Instant since,
        @Schema(requiredMode = RequiredMode.REQUIRED) long completeRepositories,
        @Schema(requiredMode = RequiredMode.REQUIRED) long totalRepositories) {}
