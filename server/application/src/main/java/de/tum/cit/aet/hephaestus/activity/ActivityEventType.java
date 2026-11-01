package de.tum.cit.aet.hephaestus.activity;

/**
 * Activity event types - ONLY types that are actually emitted.
 *
 * <p>Each type here has a corresponding handler in ActivityEventListener
 * that listens to a domain event and persists to the ledger.
 *
 * <p>Maps to DX Core 4 dimensions:
 * <ul>
 *   <li><strong>Speed</strong>: PULL_REQUEST_OPENED → PULL_REQUEST_MERGED (lead time)</li>
 *   <li><strong>Effectiveness</strong>: REVIEW_* (review cycle time)</li>
 *   <li><strong>Quality</strong>: via payload attributes</li>
 * </ul>
 */
public enum ActivityEventType {
    // Pull Request Lifecycle (Speed metrics)
    /** Pull request created - starts the lead time clock */
    PULL_REQUEST_OPENED("pull_request.opened"),
    /** Pull request merged - ends the lead time clock */
    PULL_REQUEST_MERGED("pull_request.merged"),
    /** Pull request closed without merge */
    PULL_REQUEST_CLOSED("pull_request.closed"),
    /** Pull request reopened - lifecycle tracking */
    PULL_REQUEST_REOPENED("pull_request.reopened"),
    /** Pull request marked ready for review (draft->ready transition) */
    PULL_REQUEST_READY("pull_request.ready"),
    /** Pull request converted to draft (ready->draft transition) */
    PULL_REQUEST_DRAFTED("pull_request.drafted"),
    /** Pull request synchronized - new commits pushed to the branch */
    PULL_REQUEST_SYNCHRONIZED("pull_request.synchronized"),
    /** Label added to pull request - workflow tracking */
    PULL_REQUEST_LABELED("pull_request.labeled"),
    /** Label removed from pull request - workflow tracking */
    PULL_REQUEST_UNLABELED("pull_request.unlabeled"),

    // Review Lifecycle (Effectiveness metrics)
    /** Review approved - ends "time to approval" clock */
    REVIEW_APPROVED("review.approved"),
    /** Review requested changes */
    REVIEW_CHANGES_REQUESTED("review.changes_requested"),
    /** Review with comments only (no approval/rejection) */
    REVIEW_COMMENTED("review.commented"),
    /** Review with unknown state (API returned unknown/pending state) */
    REVIEW_UNKNOWN("review.unknown"),
    /** Review dismissed - the original review stays counted */
    REVIEW_DISMISSED("review.dismissed"),
    /** Review edited - state or content changed (e.g., COMMENTED → APPROVED) */
    REVIEW_EDITED("review.edited"),

    // Comments
    /** Comment created on issue or pull request (general discussion) */
    COMMENT_CREATED("comment.created"),
    /** Comment updated - audit trail */
    COMMENT_UPDATED("comment.updated"),
    /** Comment deleted - audit trail */
    COMMENT_DELETED("comment.deleted"),
    /** Inline code review comment created (higher-value feedback) */
    REVIEW_COMMENT_CREATED("review_comment.created"),
    /** Inline code review comment edited - audit trail */
    REVIEW_COMMENT_EDITED("review_comment.edited"),
    /** Inline code review comment deleted - audit trail */
    REVIEW_COMMENT_DELETED("review_comment.deleted"),

    // Review Threads (Code review effectiveness metrics)
    /** Review thread resolved - indicates addressed feedback */
    REVIEW_THREAD_RESOLVED("review_thread.resolved"),
    /** Review thread unresolved - indicates reopened discussion */
    REVIEW_THREAD_UNRESOLVED("review_thread.unresolved"),

    // Issue Lifecycle (Work tracking)
    /** Issue created - drives work creation */
    ISSUE_CREATED("issue.created"),
    /** Issue closed - work completion signal */
    ISSUE_CLOSED("issue.closed"),
    /** Issue reopened - work resumption signal */
    ISSUE_REOPENED("issue.reopened"),
    /** Issue deleted - audit trail */
    ISSUE_DELETED("issue.deleted"),
    /** Label added to issue - workflow tracking */
    ISSUE_LABELED("issue.labeled"),
    /** Label removed from issue - workflow tracking */
    ISSUE_UNLABELED("issue.unlabeled"),
    /** Issue type assigned (e.g., bug, feature, task) - work categorization */
    ISSUE_TYPED("issue.typed"),
    /** Issue type removed - work categorization change */
    ISSUE_UNTYPED("issue.untyped"),

    // Commit Events (Code contribution tracking)
    /** Commit created (pushed to default branch) */
    COMMIT_CREATED("commit.created");

    private final String value;

    ActivityEventType(String value) {
        this.value = value;
    }

    /**
     * Get the string value stored in the database.
     */
    public String getValue() {
        return value;
    }

    /**
     * Convert from database string value to enum.
     *
     * @param value the database value
     * @return the matching enum
     * @throws IllegalArgumentException if value is null or unknown
     */
    public static ActivityEventType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Event type value cannot be null");
        }
        for (ActivityEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
