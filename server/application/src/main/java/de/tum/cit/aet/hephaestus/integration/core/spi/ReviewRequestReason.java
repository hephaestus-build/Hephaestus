package de.tum.cit.aet.hephaestus.integration.core.spi;

public enum ReviewRequestReason {
    GATE_WITHHELD,
    COOLDOWN_ACTIVE,
    REQUEST_COOLDOWN_ACTIVE,
    REQUESTER_QUOTA_EXHAUSTED,
    CONCURRENT_DUPLICATE,
    COALESCED,
    OUT_OF_REVIEW_SCOPE,
    BOT_AUTHOR,
    BOT_REVIEWER,
    STALE_ROLLOUT_REVISION,
    WORKSPACE_INACTIVE,
    PRACTICES_DISABLED,
    NO_ACTIVE_PRACTICE,
    REVIEW_MODEL_UNBOUND,
    MEMBER_AI_DECLINED,
    PERSON_DATA_ERASED,
    FEEDBACK_MODE_OFF,
    SUBJECT_UNLINKED,
    MERGE_ACTOR_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    BUDGET_EXHAUSTED,
    ITEM_NOT_VISIBLE,
    VERSION_SUPERSEDED,
    PENDING_DEADLINE_EXCEEDED,
    ITEM_GONE,
    /** A released review signal keyed by the pull request head; it names no review to resume. */
    LEGACY_REVIEW_UNBOUND,
    /** The occasion kept no usable record of the work as it was; the current work is never reviewed in its place. */
    OCCASION_EVIDENCE_UNAVAILABLE,
    /** The occasion waits in its item's quiet window, which a newer occasion of the item extends up to the cap. */
    QUIET_WINDOW,
    /** A practice the window selects is still measured by an earlier review of the item; the window waits for it. */
    REVIEW_IN_PROGRESS,
    /** Every practice the occasion selects already has a current result or is being measured. */
    ALREADY_CURRENT;
}
