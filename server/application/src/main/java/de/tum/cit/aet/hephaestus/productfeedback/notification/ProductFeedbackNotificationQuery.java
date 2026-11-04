package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.util.List;
import java.util.UUID;

/** Checks that queued notifications still refer to retained product feedback, without exposing its content. */
public interface ProductFeedbackNotificationQuery {
    boolean exists(UUID feedbackId);

    /**
     * The GitHub issues of these cleanup records that still wait for a maintainer, one line each: a link, or the
     * repository and the marker that the issue body carries when the number is unknown. Confirmed steps are left out.
     */
    List<String> waitingCleanupIssues(List<UUID> cleanupIds);
}
