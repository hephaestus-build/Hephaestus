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
    FILE_ISSUE(false, true);

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
