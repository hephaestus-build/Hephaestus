package de.tum.cit.aet.hephaestus.practices.spi;

/** No attempt may reset the three-edit budget of one composition. */
public final class FeedbackEditBudgetSpentException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public FeedbackEditBudgetSpentException() {
        super("Budget spent: stop and report. This composition already accepted three quiet edits.");
    }
}
