package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;

/**
 * The span the page reads evidence over. Each window is checked against {@link CohortPrivacyPolicy} on its own.
 *
 * <p>{@link #TERM} is the standing's own look-back for now. Which dates a term spans, and who sets them for a
 * workspace, is an open decision recorded in ADR 0051; until it is taken a term reads the same evidence as
 * {@link #DAYS_90}.
 */
public enum PracticesAcrossWorkspaceWindow {
    TERM(PracticeStandingService.LOOKBACK_DAYS),
    DAYS_30(30),
    DAYS_90(90);

    private final int days;

    PracticesAcrossWorkspaceWindow(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}
