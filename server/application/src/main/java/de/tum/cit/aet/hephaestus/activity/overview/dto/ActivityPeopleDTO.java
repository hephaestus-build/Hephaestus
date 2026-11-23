package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityNameOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

public record ActivityPeopleDTO(
        @NonNull Instant from,
        @NonNull Instant to,

        @ActivityNameOrder @Schema(description = "Contributors in name order") @NonNull
        List<ActivityPersonDTO> people,

        @ActivityNameOrder @Schema(description = "Automation accounts in name order") @NonNull
        List<ActivityPersonDTO> automation,

        @NonNull ActivityCoverageDTO coverage,
        @NonNull ActivityHighlightsDTO highlights,
        @NonNull List<ActivityRepositoryDTO> repositories,
        @NonNull List<ActivityTeamDTO> teams) {}
