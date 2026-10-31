package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedCommentsDTO;
import org.jspecify.annotations.Nullable;

/**
 * Container for a Pull Request DTO with its embedded conversation comments, reviews, and review
 * threads, fetched inline with PRs during sync to eliminate N+1 queries.
 * <p>
 * <h2>Embedded Data</h2>
 * <ul>
 *   <li>{@code embeddedComments} - First 10 conversation comments (pagination for PRs with more)</li>
 *   <li>{@code embeddedReviews} - First 5 reviews (pagination for PRs with more)</li>
 *   <li>{@code embeddedReviewThreads} - First 5 review threads with their comments</li>
 * </ul>
 * <p>
 * Conversation comments are the top-level {@code IssueComment}s on the PR's conversation tab —
 * distinct from review-thread comments, and reusing the issue-comment DTO because GitHub models
 * both with the same type.
 */
public record PullRequestWithReviewThreads(
        GitHubPullRequestDTO pullRequest,
        EmbeddedCommentsDTO embeddedComments,
        EmbeddedReviewsDTO embeddedReviews,
        EmbeddedReviewThreadsDTO embeddedReviewThreads) {
    /**
     * Creates a PullRequestWithReviewThreads from a GraphQL GHPullRequest model.
     *
     * @param ghPullRequest the GraphQL GHPullRequest (may be null)
     * @return PullRequestWithReviewThreads or null if ghPullRequest is null
     */
    @Nullable
    public static PullRequestWithReviewThreads fromPullRequest(@Nullable GHPullRequest ghPullRequest) {
        if (ghPullRequest == null) {
            return null;
        }

        String context = "PR #" + ghPullRequest.getNumber();

        GitHubPullRequestDTO dto = GitHubPullRequestDTO.fromPullRequest(ghPullRequest);
        if (dto == null) {
            return null;
        }
        EmbeddedCommentsDTO comments = EmbeddedCommentsDTO.fromConnection(ghPullRequest.getComments(), context);
        EmbeddedReviewsDTO reviews = EmbeddedReviewsDTO.fromConnection(ghPullRequest.getReviews(), context);
        EmbeddedReviewThreadsDTO threads =
                EmbeddedReviewThreadsDTO.fromConnection(ghPullRequest.getReviewThreads(), context);

        return new PullRequestWithReviewThreads(dto, comments, reviews, threads);
    }
}
