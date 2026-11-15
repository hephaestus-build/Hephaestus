package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * The authority of one conversation copy write: the Hephaestus turn whose attempt claimed it, and when. The receipt must
 * name the same turn and settle after this time.
 */
public record CopyWriteFence(UUID feedbackId, UUID copyId, UUID conversationTurnId, Instant writeStartedAt) {}
