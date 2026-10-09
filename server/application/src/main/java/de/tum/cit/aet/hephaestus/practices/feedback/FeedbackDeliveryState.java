package de.tum.cit.aet.hephaestus.practices.feedback;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum FeedbackDeliveryState {
    AWAITING_APPROVAL,
    PREPARED,
    PARTIALLY_DELIVERED,
    PARTIALLY_FAILED,
    DELIVERED,
    SUPERSEDED,
    SUPPRESSED,
    FAILED,
    DISCARDED,
    /**
     * Terminal: conversation feedback a completed turn linked with no record that the feedback was shown. Its
     * visibility is unknown, so it counts as neither delivered nor withheld and is never prepared again.
     */
    UNCONFIRMED;

    /** Feedback that reached its recipient: delivered, partly delivered, or replaced after it was delivered. */
    public static final Set<FeedbackDeliveryState> REACHED =
            Collections.unmodifiableSet(EnumSet.of(DELIVERED, PARTIALLY_DELIVERED, SUPERSEDED));
}
