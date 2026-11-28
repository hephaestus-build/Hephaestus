package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.UUID;

public record ReviewQueueJob(
        UUID jobId, UUID reviewId, long itemId, ReviewJobStatus status, boolean heldForBudget, Instant since) {}
