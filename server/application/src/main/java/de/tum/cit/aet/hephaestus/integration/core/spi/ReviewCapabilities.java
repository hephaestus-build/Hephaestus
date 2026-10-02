package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import java.util.Set;

/** Review limits and the finite authoring choices supported by one artifact domain. */
public record ReviewCapabilities(
        List<ReviewLimitation> reviewLimitations,
        List<ReviewStateDimension> reviewWhenDimensions,
        Set<String> preconditionSupportedAspects,
        Set<String> preconditionEvidenceCollections) {
    public static final ReviewCapabilities NONE = new ReviewCapabilities(List.of(), List.of(), Set.of(), Set.of());

    public ReviewCapabilities {
        reviewLimitations = List.copyOf(reviewLimitations);
        reviewWhenDimensions = List.copyOf(reviewWhenDimensions);
        preconditionSupportedAspects = Set.copyOf(preconditionSupportedAspects);
        preconditionEvidenceCollections = Set.copyOf(preconditionEvidenceCollections);
    }
}
