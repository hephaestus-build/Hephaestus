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
     * Its own reason rather than a gate skip because it is retryable: linking the account afterwards, or the
     * member sync admitting the author, makes everything passed over reviewable again, and {@link #GATE_SKIPPED}
     * would make it terminal silently.
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
     * One sentence per reason, for every surface that has to explain a silence — the trace timeline, the
     * trace's per-practice explanation and the answer to a review somebody asked for. It lives beside the
     * reason because a second hand-written copy is how a cooldown comes to be reported as an exhausted
     * budget, sending an operator to raise a cap that was never set.
     *
     * <p>Written for any reader: the developer whose work it is and an admin reading about somebody else's
     * work see the same sentence, so it states the fact in the product's words and leaves the fix to a link
     * only a reader who can act on it is shown.
     */
    public String describe() {
        return switch (this) {
            case GATE_SKIPPED -> "This workspace's review settings turned it away.";
            case COOLDOWN_ACTIVE -> "This work was already reviewed within the workspace's cooldown period.";
            case REQUEST_COOLDOWN_ACTIVE -> "A review of this work was already asked for recently.";
            case REQUESTER_QUOTA_EXHAUSTED ->
                "Whoever asked has used up their review requests for this hour; the allowance refills.";
            case CONCURRENT_DUPLICATE -> "The same review was already running.";
            case COALESCED -> "A later change to this work replaced this update before a review started.";
            case OUT_OF_REVIEW_SCOPE ->
                "The author, repository or base branch is outside the workspace's review coverage.";
            case STALE_ROLLOUT_REVISION ->
                "The workspace's review settings changed before this review could start, so it did not run.";
            case WORKSPACE_INACTIVE -> "The workspace was not active; it is tried again once it is.";
            case PRACTICES_DISABLED ->
                "Practice reviews are switched off for this workspace; it is tried again once they are on.";
            case NO_ACTIVE_PRACTICE -> "No practice was watching for this when it happened.";
            case MEMBER_AI_DECLINED -> "The developer has not allowed AI practice reviews of their work.";
            case REVIEW_MODEL_UNBOUND -> "No AI model is set up to run practice reviews in this workspace.";
            case PRACTICE_AUTONOMY_OFF -> "Every practice watching for this is turned off.";
            case BUDGET_EXHAUSTED -> "The workspace's AI budget was used up; it is tried again once it refills.";
            case SUBJECT_UNLINKED ->
                "The author is unknown or not yet a member of this workspace; it is tried again once they are.";
            case MODEL_UNAVAILABLE -> "The AI model set up for practice reviews is no longer available.";
            case ARTIFACT_NOT_VISIBLE ->
                "This work is not showing at its provider right now; it is tried again if it comes back.";
            case PENDING_DEADLINE_EXCEEDED -> "It waited too long to be picked up for review.";
            case ARTIFACT_GONE -> "This work no longer exists or can no longer be reviewed.";
        };
    }
}
