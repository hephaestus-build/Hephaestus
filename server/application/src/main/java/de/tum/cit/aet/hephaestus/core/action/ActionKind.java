package de.tum.cit.aet.hephaestus.core.action;

/**
 * What an action does in the outside world. The database CHECK lists the same names, and each kind has exactly one
 * {@link ActionAdapter}.
 */
public enum ActionKind {
    /**
     * One email through the SMTP relay. Email has no readback, so it is sent at most once: an attempt whose write may
     * have started ends {@link ActionState#UNCERTAIN} and is never sent again. It may belong to no workspace.
     */
    EMAIL(true, true),

    /**
     * One product-feedback issue on GitHub, for the instance. GitHub can list the app's issues by their marker, so an
     * attempt whose write may have started reads back before it writes again.
     */
    FILE_ISSUE(false, true),

    /**
     * Posts the copies of one feedback on the reviewed work, or reads back copies whose write may have reached the
     * provider. Each copy carries a marker, so an attempt whose write may have started reads back first.
     */
    DELIVER_FEEDBACK(false, false),

    /** Corrects the posted copies of one feedback in place, to a newer revision. */
    EDIT_FEEDBACK(false, false),

    /** Removes or restores the posted copies of one feedback. */
    RETRACT_FEEDBACK(false, false),

    /**
     * One Slack message of Hephaestus: the reply to a Slack turn, or a weekly digest's note. Each carries its entry in
     * its metadata, so an attempt whose post may have reached Slack reads the history first.
     */
    SLACK_POST(false, false),

    /**
     * Publishes a private product-feedback issue as a new public one, for the instance, on a maintainer's
     * {@code /publish}. The new issue carries a marker, so an attempt whose write may have started looks for it first.
     */
    PUBLISH_REPORT(false, true),

    /**
     * Scrubs the GitHub issue of an erased product-feedback report, for the instance: moves a public one to the private
     * repository, then replaces its text and closes it. Each step can be read back or repeated safely.
     */
    CLEANUP_ISSUE(false, true),

    /**
     * Moves a reporter's public product-feedback issue to the private repository, for the instance. The moved issue
     * keeps its marker, so an attempt whose write may have started looks for it there first.
     */
    TRANSFER_ISSUE(false, true);

    private final boolean atMostOnce;
    private final boolean instanceLevel;

    ActionKind(boolean atMostOnce, boolean instanceLevel) {
        this.atMostOnce = atMostOnce;
        this.instanceLevel = instanceLevel;
    }

    /**
     * Whether the provider offers no readback. Then an attempt whose write may have started is never taken again; the
     * database refuses it for these kinds.
     */
    public boolean atMostOnce() {
        return atMostOnce;
    }

    /** Whether an action of this kind may belong to no workspace, such as an email to an account. */
    public boolean instanceLevel() {
        return instanceLevel;
    }
}
