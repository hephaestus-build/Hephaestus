package de.tum.cit.aet.hephaestus.activity.overview.dto;

import org.jspecify.annotations.NonNull;

public record ActivityTeamDTO(
        @NonNull Long id, @NonNull String key, @NonNull String name) {}
