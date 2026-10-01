package de.tum.cit.aet.hephaestus.practices.feedback;

/** Why an entire feedback unit, or the remaining placements of a partially delivered package, were withheld. */
public enum FeedbackSuppressionReason {
    VOLUME_CAPPED,
    COMPOSER_DEDUPED,
    /** The composer decided, with a reason, that this practice's feedback should not go on the work. */
    COMPOSER_WITHHELD,
    /**
     * The note, as it would appear, is word for word the one last delivered on the same work to the same person;
     * posting it again would say nothing new.
     */
    REPEATS_DELIVERED_NOTE,
    REACTED_DISPUTED,
    REACTED_NOT_APPLICABLE,
    CONVERSATION_EXPIRED,
    ARTIFACT_GONE,
    ARTIFACT_CLOSED,
    ISSUE_SNAPSHOT_CHANGED,
    ARTIFACT_MERGED,
    /** @deprecated Read compatibility only; no current policy emits this reason. */
    @Deprecated
    ARTIFACT_DRAFT,
    RECIPIENT_OPTED_OUT,
    EMPTY_AFTER_SANITIZE,
    INSTANCE_SILENCED,
    WORKSPACE_DISABLED,
    WORKSPACE_DELIVERY_PAUSED,
    STALE_ROLLOUT_REVISION,
    OUTSIDE_CURRENT_COVERAGE,
    APPROVAL_STALE,
    APPROVAL_NO_LONGER_ELIGIBLE,
    PRACTICE_REQUIRES_APPROVAL,
    BACKFILL_QUIET,
    /** A workspace admin invalidated an observation this feedback cites. */
    OBSERVATION_INVALIDATED,
}
