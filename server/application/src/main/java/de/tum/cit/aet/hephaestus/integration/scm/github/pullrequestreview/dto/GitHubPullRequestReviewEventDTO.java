package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub pull_request_review webhook events.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestReviewEventDTO(
        @JsonProperty("action") String action,
        @JsonProperty("review") GitHubReviewDTO review,
        @JsonProperty("pull_request") GitHubPullRequestDTO pullRequest,
        @JsonProperty("repository") GitHubRepositoryRefDTO repository,
        @JsonProperty("sender") GitHubUserDTO sender)
        implements GitHubWebhookEvent {
    @Override
    public GitHubEventAction.PullRequestReview actionType() {
        return GitHubEventAction.PullRequestReview.fromString(action);
    }

    @Override
    public GitHubRepositoryRefDTO repository() {
        return repository;
    }

    /**
     * DTO for the review within the event.
     * Provides factory methods for creating from both REST (webhook) and GraphQL responses.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubReviewDTO(
            @JsonProperty("id") @Nullable Long id,
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("body") String body,
            @JsonProperty("state") @Nullable String state,
            @JsonProperty("html_url") @Nullable String htmlUrl,
            @JsonProperty("user") @Nullable GitHubUserDTO author,
            @JsonProperty("submitted_at") @Nullable Instant submittedAt,
            @JsonProperty("commit_id") @Nullable String commitId,
            @Nullable Instant createdAt,
            @JsonProperty("updated_at") @Nullable Instant updatedAt,
            @Nullable Boolean authorCanPushToRepository) {
        // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

        /**
         * Creates a GitHubReviewDTO from a GraphQL GHPullRequestReview model.
         * Uses fullDatabaseId instead of databaseId to avoid integer overflow.
         */
        @Nullable
        public static GitHubReviewDTO fromPullRequestReview(@Nullable GHPullRequestReview review) {
            if (review == null) {
                return null;
            }
            return new GitHubReviewDTO(
                    review.getFullDatabaseId() != null
                            ? review.getFullDatabaseId().longValue()
                            : null,
                    review.getId(),
                    review.getBody(),
                    review.getState() != null ? review.getState().name() : null,
                    uriToString(review.getUrl()),
                    GitHubUserDTO.fromActor(review.getAuthor()),
                    toInstant(review.getSubmittedAt()),
                    review.getCommit() != null ? review.getCommit().getOid() : null,
                    toInstant(review.getCreatedAt()),
                    toInstant(review.getUpdatedAt()),
                    review.getAuthorCanPushToRepository());
        }
    }
}
