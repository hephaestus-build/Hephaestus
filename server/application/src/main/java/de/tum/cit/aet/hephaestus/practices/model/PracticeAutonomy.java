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
     * Whether this autonomy lets feedback reach {@code channel} without a person approving it first.
     * {@code HUMAN_APPROVAL} gates only the pushed channels ({@link FeedbackChannel#pushed()}): a note on
     * the work is public and reversible only by deleting it, so a person releases it. A practice page or a
     * chat turn is read by the subject on request, so every practice that admits review at all delivers
     * there. {@code OFF} delivers nowhere; {@code AUTOMATIC} everywhere.
     */
    public boolean delivers(FeedbackChannel channel) {
        return channel.pushed() ? deliversWithoutApproval() : admitsReview();
    }
}
