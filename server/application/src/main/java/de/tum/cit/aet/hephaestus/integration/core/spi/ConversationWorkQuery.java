package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Workspace-scoped work summaries supplied by the connected domain adapter. Each read covers only the workspace's
 * connected instance, repositories the workspace monitors and no team hides from contributions, and work that is not
 * tombstoned.
 */
public interface ConversationWorkQuery {
    ConversationUserCounts fetchUserCounts(
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
