package de.tum.cit.aet.hephaestus.activity.overview.dto;

import org.jspecify.annotations.NonNull;

public record ActivityRepositoryCountsDTO(
        @NonNull ActivityRepositoryDTO repository,
        @NonNull ActivityCountsDTO counts,
        @NonNull ActivitySummaryDTO breakdown) {}
