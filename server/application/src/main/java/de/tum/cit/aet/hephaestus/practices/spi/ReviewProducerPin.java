package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * The producer of a review's outcomes, chosen at the review's first seal and never again (ADR 0160). The caller holds
 * the review's write lock, so two seals of one review cannot both choose.
 */
public interface ReviewProducerPin {
    /** The pinned producer; empty while no seal chose one. A review that a server before the pin wrote is LEGACY. */
    Optional<FeedbackProducer> pinned(long workspaceId, UUID reviewId);

    /** Pins the first choice of a review that has none. */
    void pin(long workspaceId, UUID reviewId, FeedbackProducer producer);
}
