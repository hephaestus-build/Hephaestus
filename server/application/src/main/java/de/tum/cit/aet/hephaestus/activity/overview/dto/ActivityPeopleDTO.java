package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ActivityPeopleDTO(
        @NonNull Instant from,
        @NonNull Instant to,
        @NonNull List<ActivityPersonDTO> people,
        @NonNull List<ActivityPersonDTO> automation,
        @NonNull ActivityCoverageDTO coverage,
        @NonNull ActivityHighlightsDTO highlights,
        @NonNull List<ActivityRepositoryDTO> repositories,
        @NonNull List<ActivityTeamDTO> teams,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Maximum range width in days")
        int maxRangeDays,

        @Nullable Instant historyStart) {}
