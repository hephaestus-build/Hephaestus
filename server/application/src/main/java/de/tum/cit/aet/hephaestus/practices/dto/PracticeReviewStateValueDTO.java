package de.tum.cit.aet.hephaestus.practices.dto;

import org.jspecify.annotations.NonNull;

public record PracticeReviewStateValueDTO(
        @NonNull String value, @NonNull String displayName) {}
