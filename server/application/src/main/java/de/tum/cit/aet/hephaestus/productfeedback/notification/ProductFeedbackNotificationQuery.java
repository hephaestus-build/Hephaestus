package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.util.UUID;

/** Checks that queued notifications still refer to retained product feedback, without exposing its content. */
public interface ProductFeedbackNotificationQuery {
    boolean exists(UUID feedbackId);

    /** Whether an erased report's cleanup still waits for a maintainer; false once an admin confirmed it. */
    boolean cleanupNeedsAction(UUID cleanupId);
}
