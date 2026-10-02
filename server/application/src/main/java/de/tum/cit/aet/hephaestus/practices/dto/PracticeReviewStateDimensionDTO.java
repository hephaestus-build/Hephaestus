package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

public record PracticeReviewStateDimensionDTO(
        @NonNull String key,
        @NonNull String displayName,
        @NonNull List<PracticeReviewStateValueDTO> values,
        @NonNull Set<String> recommendedValues) {
    public static PracticeReviewStateDimensionDTO from(ReviewStateDimension dimension) {
        return new PracticeReviewStateDimensionDTO(
                dimension.key(),
                dimension.displayName(),
                dimension.values().stream()
                        .map(value -> new PracticeReviewStateValueDTO(value.value(), value.displayName()))
                        .toList(),
                dimension.recommendedValues());
    }
}
