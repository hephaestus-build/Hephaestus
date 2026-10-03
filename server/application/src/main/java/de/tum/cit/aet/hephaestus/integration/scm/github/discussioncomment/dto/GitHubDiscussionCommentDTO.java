package de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCommentAuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHDiscussionComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHDiscussionCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Domain DTO for GitHub Discussion Comments.
 * <p>
 * This is the unified model used by both GraphQL sync and webhook handlers.
 * It can be constructed from any source (GraphQL, REST, webhook payload).
 * <p>
 * Discussion comments can be top-level comments on a discussion or replies
 * to other comments (threaded). The replyToId field indicates the parent
 * comment's node ID if this is a reply.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubDiscussionCommentDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("node_id") @Nullable String nodeId,
        @JsonProperty("body") @Nullable String body,
        @JsonProperty("html_url") @Nullable String htmlUrl,
        @JsonProperty("is_answer") boolean isAnswer,
        @JsonProperty("is_minimized") boolean isMinimized,
        @JsonProperty("minimized_reason") @Nullable String minimizedReason,
        @JsonProperty("author_association") @Nullable String authorAssociation,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,
        @JsonProperty("user") @Nullable GitHubUserDTO author,
        // For reply threading - contains the parent comment's node ID
        @JsonProperty("reply_to_id") @Nullable String replyToNodeId) {
    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     * <p>
     * Note: Unlike Discussion, DiscussionComment in GraphQL DOES have databaseId.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubDiscussionCommentDTO from a GraphQL DiscussionComment model.
     *
     * @param comment the GraphQL DiscussionComment (may be null)
     * @return GitHubDiscussionCommentDTO or null if comment is null
     */
    @Nullable
    public static GitHubDiscussionCommentDTO fromDiscussionComment(@Nullable GHDiscussionComment comment) {
        if (comment == null) {
            return null;
        }

        return new GitHubDiscussionCommentDTO(
                null,
                comment.getDatabaseId() != null ? comment.getDatabaseId().longValue() : null,
                comment.getId(),
                comment.getBody(),
                uriToString(comment.getUrl()),
                comment.getIsAnswer(),
                comment.getIsMinimized(),
                comment.getMinimizedReason(),
                convertAuthorAssociation(comment.getAuthorAssociation()),
                toInstant(comment.getCreatedAt()),
                toInstant(comment.getUpdatedAt()),
                GitHubUserDTO.fromActor(comment.getAuthor()),
                comment.getReplyTo() != null ? comment.getReplyTo().getId() : null);
    }

    /**
     * Creates a list of GitHubDiscussionCommentDTOs from a GraphQL DiscussionCommentConnection.
     * <p>
     * Extracts both top-level comment nodes AND their nested replies (from the
     * {@code replies(first: 10)} sub-connection). Reply DTOs have their
     * {@code replyToNodeId} set to the parent comment's node ID for threading.
     */
    public static List<GitHubDiscussionCommentDTO> fromDiscussionCommentConnection(
            @Nullable GHDiscussionCommentConnection connection) {
        if (connection == null || connection.getNodes() == null) {
            return Collections.emptyList();
        }
        List<GitHubDiscussionCommentDTO> result = new ArrayList<>();
        for (var comment : connection.getNodes()) {
            GitHubDiscussionCommentDTO dto = fromDiscussionComment(comment);
            if (dto != null) {
                result.add(dto);
                // Also extract nested replies
                extractReplies(comment, result);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Extracts reply DTOs from a comment's {@code replies} sub-connection.
     * <p>
     * For each reply, the {@code replyToNodeId} is set to the parent comment's
     * node ID (since the reply nodes in the GraphQL query may not include
     * {@code replyTo { id }}).
     */
    private static void extractReplies(GHDiscussionComment parentComment, List<GitHubDiscussionCommentDTO> result) {
        if (parentComment == null
                || parentComment.getReplies() == null
                || parentComment.getReplies().getNodes() == null) {
            return;
        }
        String parentNodeId = parentComment.getId();
        for (var reply : parentComment.getReplies().getNodes()) {
            if (reply == null) {
                continue;
            }
            GitHubDiscussionCommentDTO replyDto = new GitHubDiscussionCommentDTO(
                    null,
                    reply.getDatabaseId() != null ? reply.getDatabaseId().longValue() : null,
                    reply.getId(),
                    reply.getBody(),
                    uriToString(reply.getUrl()),
                    reply.getIsAnswer(),
                    reply.getIsMinimized(),
                    reply.getMinimizedReason(),
                    convertAuthorAssociation(reply.getAuthorAssociation()),
                    toInstant(reply.getCreatedAt()),
                    toInstant(reply.getUpdatedAt()),
                    GitHubUserDTO.fromActor(reply.getAuthor()),
                    parentNodeId // set replyToNodeId to the parent comment's node ID
                    );
            result.add(replyDto);
        }
    }

    // CONVERSION HELPERS

    @Nullable
    private static String convertAuthorAssociation(@Nullable GHCommentAuthorAssociation association) {
        if (association == null) {
            return null;
        }
        return association.name();
    }
}
