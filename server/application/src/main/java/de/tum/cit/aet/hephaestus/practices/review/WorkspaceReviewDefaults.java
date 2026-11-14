package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPolicy;

public record WorkspaceReviewDefaults(FeedbackMode defaultFeedbackMode) {
    public static final WorkspaceReviewDefaults UNSET = new WorkspaceReviewDefaults(FeedbackMode.APPROVE_FIRST);

    public static WorkspaceReviewDefaults of(PracticeReviewPolicy settings) {
        return settings.defaultFeedbackMode() == null
                ? UNSET
                : new WorkspaceReviewDefaults(FeedbackMode.valueOf(settings.defaultFeedbackMode()));
    }
}
