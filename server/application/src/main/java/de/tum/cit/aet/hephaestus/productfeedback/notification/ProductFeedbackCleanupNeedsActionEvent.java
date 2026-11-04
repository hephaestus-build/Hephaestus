package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Erased reports' GitHub cleanups that now wait for a maintainer, from one sweep; it carries no person data. */
public record ProductFeedbackCleanupNeedsActionEvent(List<UUID> cleanupIds, Instant since) {}
