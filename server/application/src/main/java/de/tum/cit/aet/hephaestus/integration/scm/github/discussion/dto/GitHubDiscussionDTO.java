package de.tum.cit.aet.hephaestus.integration.scm.github.discussion.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment.dto.GitHubDiscussionCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHDiscussion;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHDiscussionStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHLockReason;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Domain DTO for GitHub Discussions.
 * <p>
 * This is the unified model used by both GraphQL sync and webhook handlers.
 * It can be constructed from any source (GraphQL, REST, webhook payload).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubDiscussionDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("node_id") @Nullable String nodeId,
        @JsonProperty("number") int number,
        @JsonProperty("title") @Nullable String title,
        @JsonProperty("body") @Nullable String body,
        @JsonProperty("html_url") @Nullable String htmlUrl,
        @JsonProperty("state") @Nullable String state,
        @JsonProperty("state_reason") @Nullable String stateReason,
        @JsonProperty("locked") boolean locked,
        @JsonProperty("active_lock_reason") @Nullable String activeLockReason,
        @JsonProperty("comments") int commentsCount,
        @JsonProperty("upvote_count") int upvoteCount,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,
        @JsonProperty("closed_at") @Nullable Instant closedAt,
        @JsonProperty("answer_chosen_at") @Nullable Instant answerChosenAt,
        @JsonProperty("user") @Nullable GitHubUserDTO author,
        @JsonProperty("answer_chosen_by") @Nullable GitHubUserDTO answerChosenBy,
        @JsonProperty("category") @Nullable GitHubDiscussionCategoryDTO category,
        @JsonProperty("labels") @Nullable List<GitHubLabelDTO> labels,
        // Embedded answer comment for GraphQL sync
        @JsonProperty("answer") @Nullable GitHubDiscussionCommentDTO answerComment) {
    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    /**
     * Check if the discussion is closed.
     */
    public boolean isClosed() {
        return "closed".equalsIgnoreCase(state);
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubDiscussionDTO from a GraphQL Discussion model.
     *
     * @param discussion the GraphQL Discussion (may be null)
     * @return GitHubDiscussionDTO or null if discussion is null
     */
    @Nullable
    public static GitHubDiscussionDTO fromDiscussion(@Nullable GHDiscussion discussion) {
        if (discussion == null) {
            return null;
        }

        return new GitHubDiscussionDTO(
                null,
                discussion.getDatabaseId() != null ? discussion.getDatabaseId().longValue() : null,
                discussion.getId(),
                discussion.getNumber(),
                discussion.getTitle(),
                discussion.getBody(),
                uriToString(discussion.getUrl()),
                discussion.getClosed() ? "closed" : "open",
                convertStateReason(discussion.getStateReason()),
                discussion.getLocked(),
                convertLockReason(discussion.getActiveLockReason()),
                discussion.getComments() != null ? discussion.getComments().getTotalCount() : 0,
                discussion.getUpvoteCount(),
                toInstant(discussion.getCreatedAt()),
                toInstant(discussion.getUpdatedAt()),
                toInstant(discussion.getClosedAt()),
                toInstant(discussion.getAnswerChosenAt()),
                GitHubUserDTO.fromActor(discussion.getAuthor()),
                GitHubUserDTO.fromActor(discussion.getAnswerChosenBy()),
                GitHubDiscussionCategoryDTO.fromDiscussionCategory(discussion.getCategory()),
                GitHubLabelDTO.fromLabelConnection(discussion.getLabels(), "Discussion #" + discussion.getNumber()),
                GitHubDiscussionCommentDTO.fromDiscussionComment(discussion.getAnswer()));
    }

    // CONVERSION HELPERS

    @Nullable
    private static String convertStateReason(@Nullable GHDiscussionStateReason stateReason) {
        if (stateReason == null) {
            return null;
        }
        return stateReason.name().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String convertLockReason(@Nullable GHLockReason lockReason) {
        if (lockReason == null) {
            return null;
        }
        return lockReason.name().toLowerCase(Locale.ROOT);
    }
}
