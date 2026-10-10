package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ActivityPersonDetailDTO(
        @NonNull Instant from,
        @NonNull Instant to,
        @NonNull UserInfoDTO person,
        @NonNull ActivityContributorKind kind,
        @NonNull ActivityCountsDTO counts,
        @Nullable Instant firstContributionAt,
        @NonNull ActivityBreakdownDTO breakdown,
        @NonNull List<ActivityWeekDTO> weeks,
        @NonNull List<ActivityRepositoryCountsDTO> repositories) {}
