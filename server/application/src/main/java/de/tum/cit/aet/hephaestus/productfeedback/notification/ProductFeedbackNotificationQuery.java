package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.util.UUID;

/** Checks that queued notifications still refer to retained product feedback, without exposing its content. */
public interface ProductFeedbackNotificationQuery {
    long countBetween(java.time.Instant from, java.time.Instant until);

    boolean exists(UUID feedbackId);
}
