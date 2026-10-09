package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ActivityPersonDTO(
        @NonNull UserInfoDTO person,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean automation,
        @NonNull ActivityCountsDTO counts,
        @NonNull ActivitySummaryDTO breakdown,
        @Nullable Instant firstContributionAt,
        @NonNull List<ActivityWeekDTO> weeks) {}
