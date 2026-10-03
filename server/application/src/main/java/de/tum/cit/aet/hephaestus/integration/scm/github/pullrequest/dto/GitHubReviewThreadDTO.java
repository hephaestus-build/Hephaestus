package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub pull request review threads from GraphQL responses.
 * <p>
 * This DTO wraps the GraphQL review thread data for use in the service layer,
 * providing a clean abstraction over the generated GraphQL model.
 * <p>
 * Note: The {@code commentsConnection} field retains the GraphQL type because
 * it contains pagination info and nodes that are processed by
 * {@link de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService}.
 * This is intentional - the comments within threads need special handling for
 * nested pagination and are not directly exposed through the public API.
 *
 * @param commentsConnection the comments connection containing thread comments and pagination info. This is used
 *     internally by GitHubPullRequestReviewCommentSyncService for processing comments and handling nested pagination.
 *     It is not exposed through the public DTO API.
 */
public record GitHubReviewThreadDTO(
        String nodeId,
        String path,
        @Nullable Integer line,
        @Nullable Integer startLine,
        @Nullable String diffSide,
        @Nullable String startDiffSide,
        boolean isResolved,
        boolean isOutdated,
        boolean isCollapsed,
        @Nullable GitHubUserDTO resolvedBy,
        @Nullable GHPullRequestReviewCommentConnection commentsConnection) {
    /**
     * Creates a GitHubReviewThreadDTO from a GraphQL GHPullRequestReviewThread model.
     *
     * @param thread the GraphQL review thread (may be null)
     * @return GitHubReviewThreadDTO or null if thread is null
     */
    @Nullable
    public static GitHubReviewThreadDTO fromReviewThread(@Nullable GHPullRequestReviewThread thread) {
        if (thread == null) {
            return null;
        }
        return new GitHubReviewThreadDTO(
                thread.getId(),
                thread.getPath(),
                thread.getLine(),
                thread.getStartLine(),
                thread.getDiffSide() != null ? thread.getDiffSide().name() : null,
                thread.getStartDiffSide() != null ? thread.getStartDiffSide().name() : null,
                thread.getIsResolved(),
                thread.getIsOutdated(),
                thread.getIsCollapsed(),
                GitHubUserDTO.fromUser(thread.getResolvedBy()),
                thread.getComments());
    }
}
