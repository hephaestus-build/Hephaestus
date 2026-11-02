package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ProductFeedbackUpdateEmailRequested(
        UUID feedbackId,
        long accountId,
        ProductFeedbackUpdatedEvent.Kind kind,
        @Nullable String issueUrl,
        boolean publicIssue,
        @Nullable String detail,
        Instant requestedAt,
        Instant expiresAt) {}
