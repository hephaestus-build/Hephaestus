package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewValidation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * @param whyItMatters the practice's rationale, so a row is triageable without opening it
 */
public record CatalogPracticeSummaryDTO(
        @NonNull String slug,
        @NonNull String name,
        @NonNull ArtifactKind artifactKind,
        // Deliberately not criteria: that is the review rule, addressed to the model, and runs to thousands of
        // characters once its preamble is composed in.
        @Nullable String whyItMatters,
        @Nullable String groupSlug,
        @Nullable String groupName,
        @NonNull CatalogAdoptionAvailability availability,
        @NonNull PracticeAutomatedReviewValidation automatedReviewValidation) {}
