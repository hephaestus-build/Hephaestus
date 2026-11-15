package de.tum.cit.aet.hephaestus.practices.scan.dto;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ScanSummaryDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO repository,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ref,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String itemVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewOrigin origin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewStatus status,

        @Nullable AccountRefDTO requestedBy,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,

        @Nullable Instant startedAt,

        @Nullable Instant completedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int practiceCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ScanCountsDTO counts,

        @Nullable UUID baselineScanId,

        @Nullable ReviewStopReason stopReason) {}
