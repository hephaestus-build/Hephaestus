package de.tum.cit.aet.hephaestus.activity.overview.dto;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ActivityTeamDTO(
        @NonNull Long id,
        @NonNull String key,
        @NonNull String name,
        @Nullable Long parentId) {}
