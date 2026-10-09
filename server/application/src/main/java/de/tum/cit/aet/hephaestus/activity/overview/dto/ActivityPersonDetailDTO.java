package de.tum.cit.aet.hephaestus.activity.overview.dto;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

public record ActivityPersonDetailDTO(
        @NonNull Instant from,
        @NonNull Instant to,
        @NonNull ActivityPersonDTO activity,
        @NonNull List<ActivityRepositoryCountsDTO> repositories) {}
