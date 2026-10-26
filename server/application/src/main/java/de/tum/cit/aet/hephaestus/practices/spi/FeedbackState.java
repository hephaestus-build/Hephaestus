package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum FeedbackState {
    AWAITING_APPROVAL,
    READY,
    DELIVERED,
    UNCONFIRMED,
    WITHHELD,
    REJECTED,
    REPLACED,
    FAILED;

    /**
     * The states of feedback that the developer it is about may learn of, on their own pages and through Heph. A
     * proposal awaiting approval, a rejection, a withholding and a replacement stay with the workspace admins: their
     * state alone would disclose a proposal or a decision that the developer was not shown.
     */
    public static final Set<FeedbackState> VISIBLE_TO_DEVELOPER =
            Collections.unmodifiableSet(EnumSet.of(READY, DELIVERED, UNCONFIRMED, FAILED));
}
