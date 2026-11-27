package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Optional;
import java.util.UUID;

/** The producer that a review's seal recorded. The runtime implements it; without the bean, no advice is written. */
public interface ReviewFeedbackProducers {
    Optional<FeedbackProducer> producer(long workspaceId, UUID reviewId);
}
