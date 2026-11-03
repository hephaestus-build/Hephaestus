package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.time.Instant;
import java.util.UUID;

/** An erased report's GitHub cleanup now waits for a maintainer; it carries no person data. */
public record ProductFeedbackCleanupNeedsActionEvent(UUID cleanupId, Instant since) {}
