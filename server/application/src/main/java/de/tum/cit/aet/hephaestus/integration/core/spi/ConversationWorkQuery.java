package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Workspace-scoped work summaries supplied by the connected domain adapter. Each read covers only the workspace's
 * connected instance, repositories the workspace monitors and no team hides from contributions, and work that is not
 * tombstoned.
 */
public interface ConversationWorkQuery {
    /** Whether the account's work in the workspace can be read; unread work is unknown, never none. */
    enum Reach {
        READABLE,
        /** The workspace connects no GitHub or GitLab instance, or nothing from it is recorded yet. */
        NO_INSTANCE,
        /** The account has no linked identity on the workspace's connected instance. */
        NOT_LINKED;

        /** Why the account's work is unknown, in the words a conversation reads. */
        public String unknownReason() {
            return switch (this) {
                case NO_INSTANCE -> "The workspace has no connected GitHub or GitLab instance with synced work yet.";
                case NOT_LINKED ->
                    "This developer's account has no linked identity on the workspace's GitHub or GitLab instance, so"
                            + " their work there is unknown, not none.";
                case READABLE -> throw new IllegalStateException("Readable work is not unknown");
            };
        }
    }

    Reach reach(Long workspaceId, Long accountId);

    /** The account's counts; empty unless {@link #reach} is {@link Reach#READABLE}. */
    Optional<ConversationUserCounts> fetchUserCounts(
            Long workspaceId, Long accountId, Instant twoWeeksAgo, Instant weekAgo, Instant now);

    /** Open issues assigned to the account, newest first. */
    List<AssignedIssue> findAssignedOpenIssues(Long workspaceId, Long accountId);

    /** Open, ready pull requests that still wait for the account's review, newest first. */
    List<RequestedReview> findPendingReviewRequestPrs(Long workspaceId, Long accountId);

    /** @param labels label names, sorted */
    record AssignedIssue(
            long id,
            int number,
            @Nullable String title,
            @Nullable String url,
            @Nullable String repository,
            @Nullable String milestoneTitle,
            @Nullable Instant milestoneDueOn,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt,
            List<String> labels) {
        public AssignedIssue {
            labels = List.copyOf(labels);
        }
    }

    record RequestedReview(
            long id,
            int number,
            @Nullable String title,
            @Nullable String url,
            @Nullable String author,
            @Nullable String repository,
            @Nullable Instant createdAt) {}
}
