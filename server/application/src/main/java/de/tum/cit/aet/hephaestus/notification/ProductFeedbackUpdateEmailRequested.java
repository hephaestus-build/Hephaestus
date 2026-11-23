package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.event.ContentFree;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ProductFeedbackUpdateEmailRequested(
        UUID feedbackId,
        long accountId,
        ProductFeedbackUpdatedEvent.Kind kind,
        @ContentFree @Nullable String issueUrl,
        boolean publicIssue,
        Instant requestedAt,
        Instant expiresAt) {}
