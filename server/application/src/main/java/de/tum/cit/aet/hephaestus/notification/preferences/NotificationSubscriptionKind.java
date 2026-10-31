package de.tum.cit.aet.hephaestus.notification.preferences;

public enum NotificationSubscriptionKind {
    PRODUCT_FEEDBACK,
    PRODUCT_FEEDBACK_UPDATES,
    WORKSPACE_ALERTS,
    /**
     * The weekly digest in the developer's Heph conversation, with its one Slack or email note. Not an email kind:
     * the note's transport is chosen per digest, and the digest itself stays in the app.
     */
    MENTOR_DIGEST;

    public boolean enabledByDefault() {
        return this == PRODUCT_FEEDBACK_UPDATES;
    }
}
