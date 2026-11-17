package de.tum.cit.aet.hephaestus.practices.spi;

public enum ReviewStopReason {
    TIMED_OUT,
    EXECUTION_FAILED,
    PREPARATION_FAILED,
    PROCESSING_FAILED,
    PERSON_DATA_ERASED,
    STALE_ROLLOUT_REVISION,
    CANCELLED_BY_ADMIN,
    REPLACED,
    /** History only: the review was cancelled before stop reasons were recorded. Old rows keep it; no code writes it. */
    LEGACY_FAILURE,
    EXECUTION_INCOMPLETE,
    RESULT_WITHHELD,
    READINESS_REFUSED,
    SUSPECT_LISTING,
    BUDGET,
    USAGE_UNVERIFIABLE,
    DEADLINE,
    CANCELLED,
    ENUMERATION_FAILED,
    ENUMERATION_LIMIT,
    NO_SCAN_PRACTICES,
    MODEL_CHANGED,
    MODEL_UNAVAILABLE,
    ERROR,
    /** History only: the review ran before its coverage was recorded. No code writes it, and old rows keep it. */
    LEGACY_SCOPE_UNKNOWN,
    CONTRACT_CHANGED,
    NO_SANDBOX_ROOM,
    /** The results were admitted, but the attempts ended before they could confirm a decision for each destination. */
    COMPOSITION_UNCONFIRMED,
    /**
     * The model provider answered none of the review's calls, or limited their rate, until the review's bound passed. It
     * describes the provider, never the work.
     */
    PROVIDER_UNAVAILABLE,
    /**
     * The work's captured evidence was larger than a review's sandbox can hold. It describes the evidence's size, never
     * the work.
     */
    EVIDENCE_TOO_LARGE,
    /**
     * A source that the practice reads changed before it started, and no newer review of the item replaces this one.
     * It is carried: the item's next readiness edge measures the practice again. {@link #REPLACED} names the case where
     * a newer review exists.
     */
    SOURCES_MOVED,
    /**
     * The practice is owed and was never measured: the attempt ended before it started or finished, by its deadline, a
     * watchdog or a lost sandbox. It says nothing about the work. It is carried, and the run keeps its own stop reason.
     */
    NOT_RUN;
}
