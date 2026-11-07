package de.tum.cit.aet.hephaestus.practices.spi;

public enum ReviewBatchPauseReason {
    BUDGET_EXHAUSTED,
    REVIEW_MODEL_UNBOUND,
    WORKSPACE_UNAVAILABLE,
    /** A person paused this batch. Only an explicit resume can continue it. */
    PAUSED_BY_ADMIN;
}
