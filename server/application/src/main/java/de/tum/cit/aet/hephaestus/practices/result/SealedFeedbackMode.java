package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import java.util.UUID;

/** The feedback mode that a review practice recorded when its first observation was admitted (ADR 0118). */
public record SealedFeedbackMode(UUID reviewId, long practiceId, FeedbackMode mode) {}
