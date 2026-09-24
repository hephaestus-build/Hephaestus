package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent;
import java.time.Instant;
import java.util.UUID;

public record ProductFeedbackEmailRequested(
        UUID feedbackId,
        long accountId,
        Instant requestedAt,
        Instant expiresAt,
        ProductFeedbackSubmittedEvent.Kind kind) {}
