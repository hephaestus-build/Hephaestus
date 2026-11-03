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
    NO_SANDBOX_ROOM;
}
