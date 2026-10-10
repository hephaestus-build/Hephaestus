package de.tum.cit.aet.hephaestus.activity.overview.dto;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

public record ActivityPeopleDTO(
        @NonNull Instant from,
        @NonNull Instant to,
        @NonNull List<ActivityPersonDTO> people,
        @NonNull List<ActivityPersonDTO> automation,
        @NonNull ActivityCoverageDTO coverage,
        @NonNull ActivityHighlightsDTO highlights,
        @NonNull List<ActivityRepositoryDTO> repositories,
        @NonNull List<ActivityTeamDTO> teams) {}
