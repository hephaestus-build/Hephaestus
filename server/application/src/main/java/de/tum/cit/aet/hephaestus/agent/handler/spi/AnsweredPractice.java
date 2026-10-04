package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.util.Objects;
import java.util.UUID;

/**
 * A ready practice this review did not ask, because the completed review {@code reviewId} already answered it under
 * revision {@code revisionId} on exactly the code this review captured. Its standing observation is that review's.
 */
public record AnsweredPractice(String practiceSlug, long revisionId, UUID reviewId) {
    public AnsweredPractice {
        Objects.requireNonNull(practiceSlug, "practiceSlug");
        Objects.requireNonNull(reviewId, "reviewId");
    }
}
