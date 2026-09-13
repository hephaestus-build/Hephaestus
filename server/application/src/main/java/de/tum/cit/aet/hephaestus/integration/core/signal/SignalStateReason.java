package de.tum.cit.aet.hephaestus.integration.core.signal;

/**
 * Why a recorded signal ended up in the state it did — a controlled vocabulary rather than free text so
 * that "how many reviews did this instance not run last week, and why" is a {@code GROUP BY}.
 *
 * <p>Each reason decides its own resulting state, so the retryable/terminal judgement lives here rather
 * than at every refusal site: a reason is retryable exactly when the condition it names can clear on its
 * own — an operator action, a budget refill, or an ordinary sync that restores the artifact — without a
 * new occurrence. The sentence a reader sees lives in {@link #describe()}.
 */
public enum SignalStateReason {
    GATE_SKIPPED(SignalState.SUPPRESSED),

    /** Terminal: retrying later would defeat the limit the workspace asked for. */
    COOLDOWN_ACTIVE(SignalState.SUPPRESSED),

    /**
     * Not {@link #COOLDOWN_ACTIVE}: that one says a review <em>ran</em> recently, this one says an
     * <em>ask</em> was made recently and may itself have been refused. Collapsing them would send the
     * asker looking for feedback that does not exist.
     */
    REQUEST_COOLDOWN_ACTIVE(SignalState.SUPPRESSED),

    /**
     * The one limit keyed on a person rather than on the work; every other one passes twenty single
     * requests against twenty colleagues' merge requests.
     */
    REQUESTER_QUOTA_EXHAUSTED(SignalState.SUPPRESSED),

    CONCURRENT_DUPLICATE(SignalState.SUPPRESSED),

    /**
     * Superseded before admission; unlike the other {@link SignalState#SUPPRESSED} reasons, {@link
     * SignalRecorder#defer} may re-arm a coalesced row when a later live transition repeats its
     * content, since that content has never itself been decided on.
     */
    COALESCED(SignalState.SUPPRESSED),

    /**
     * Terminal rather than pending, unlike configuration reasons: it turns on facts belonging to the artifact
     * that cannot change, so widening the scope alters what happens next, not what already did.
     */
    OUT_OF_REVIEW_SCOPE(SignalState.SUPPRESSED),

    STALE_ROLLOUT_REVISION(SignalState.SUPPRESSED),

    WORKSPACE_INACTIVE(SignalState.PENDING),

    PRACTICES_DISABLED(SignalState.PENDING),

    NO_ACTIVE_PRACTICE(SignalState.PENDING),

    REVIEW_MODEL_UNBOUND(SignalState.PENDING),

    /** No choice or No AI: changing preferences affects future reviews, not a silent historical replay. */
    MEMBER_AI_DECLINED(SignalState.SUPPRESSED),

    /**
     * Separate from {@link #NO_ACTIVE_PRACTICE} on purpose: collapsing them would make "we are
     * deliberately not reviewing this" indistinguishable from "nobody ever set this up".
     */
    PRACTICE_AUTONOMY_OFF(SignalState.PENDING),

    BUDGET_EXHAUSTED(SignalState.PENDING),

    /**
     * Its own reason rather than a gate skip because it is retryable: linking the account afterwards makes
     * everything passed over reviewable again, and {@link #GATE_SKIPPED} would make it terminal silently.
     */
    SUBJECT_UNLINKED(SignalState.PENDING),

    MODEL_UNAVAILABLE(SignalState.PENDING),

    ARTIFACT_NOT_VISIBLE(SignalState.PENDING),

    PENDING_DEADLINE_EXCEEDED(SignalState.LAPSED),

    ARTIFACT_GONE(SignalState.LAPSED);

    private final SignalState resultingState;

    SignalStateReason(SignalState resultingState) {
        this.resultingState = resultingState;
    }

    public SignalState resultingState() {
        return resultingState;
    }

    /** A restatement of {@link #resultingState()}, not a second source of truth: the reaper selects on the
     * stored state. */
    public boolean isRetryable() {
        return resultingState == SignalState.PENDING;
    }

    /**
     * One sentence per reason, for every surface that has to explain a silence. It lives beside the reason
     * because a second hand-written copy is how a cooldown comes to be reported as an exhausted budget,
     * sending an operator to raise a cap that was never set.
     */
    public String describe() {
        return switch (this) {
            case GATE_SKIPPED -> "The workspace's review gate declined this occurrence.";
            case COOLDOWN_ACTIVE -> "Another review ran on this artifact inside the workspace's cooldown window.";
            case REQUEST_COOLDOWN_ACTIVE ->
                "A review of this was already asked for inside the workspace's cooldown window.";
            case REQUESTER_QUOTA_EXHAUSTED ->
                "You have asked for as many reviews as an hour allows; the allowance refills.";
            case CONCURRENT_DUPLICATE -> "Another submission for the same work carries this review.";
            case COALESCED -> "A newer issue snapshot replaced this review occasion before submission.";
            case OUT_OF_REVIEW_SCOPE ->
                "This artifact is outside the branches and repositories this workspace reviews.";
            case STALE_ROLLOUT_REVISION ->
                "The review rollout changed after this work was admitted; it is not replayed under the new configuration.";
            case WORKSPACE_INACTIVE -> "The workspace was not active; it is re-offered when the workspace is.";
            case PRACTICES_DISABLED ->
                "Practice review is switched off for this workspace; it is re-offered when it is switched on.";
            case NO_ACTIVE_PRACTICE -> "No practice was bound to this occurrence when it was recorded.";
            case MEMBER_AI_DECLINED -> "The developer has not enabled AI practice reviews in this workspace.";
            case REVIEW_MODEL_UNBOUND ->
                "No AI model is bound to practice review for this workspace; binding one in Administration re-offers it.";
            case PRACTICE_AUTONOMY_OFF ->
                "Every practice bound to this occurrence sits at Off; raising one re-offers it.";
            case BUDGET_EXHAUSTED ->
                "The budget funding this review was exhausted; it is re-offered when the budget refills.";
            case SUBJECT_UNLINKED ->
                "This work could not be attributed to anybody Hephaestus knows; resolving the author re-offers it.";
            case MODEL_UNAVAILABLE ->
                "The model this review is bound to left the catalog; re-pointing the binding re-offers it.";
            case ARTIFACT_NOT_VISIBLE ->
                "The artifact is not visible upstream right now; it is re-offered if it returns.";
            case PENDING_DEADLINE_EXCEEDED -> "It waited longer than the ledger keeps re-offering a signal.";
            case ARTIFACT_GONE -> "The artifact was deleted before a review could run.";
        };
    }
}
