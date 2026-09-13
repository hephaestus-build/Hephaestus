package de.tum.cit.aet.hephaestus.notification;

import java.time.Instant;
import java.util.UUID;

public record ProductFeedbackEmailRequested(UUID feedbackId, long accountId, Instant expiresAt) {}
