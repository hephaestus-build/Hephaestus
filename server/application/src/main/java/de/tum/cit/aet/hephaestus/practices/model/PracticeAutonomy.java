package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;

public enum PracticeAutonomy {
    OFF,
    HUMAN_APPROVAL,

    AUTOMATIC;

    public static final int MAX_LENGTH = 16;
    public static final PracticeAutonomy DEFAULT = HUMAN_APPROVAL;

    public boolean admitsReview() {
        return this != OFF;
    }

    public boolean deliversWithoutApproval() {
        return this == AUTOMATIC;
    }

    /**
     * Whether feedback can reach {@code channel} without approval. {@code HUMAN_APPROVAL}
     * requires approval only for {@linkplain FeedbackChannel#pushed() pushed channels}.
     */
    public boolean delivers(FeedbackChannel channel) {
        return channel.pushed() ? deliversWithoutApproval() : admitsReview();
    }
}
