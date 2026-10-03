package de.tum.cit.aet.hephaestus.integration.scm.github.project.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHDraftIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Item;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemContent;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldValueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemType;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectItem;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Domain DTO for GitHub Projects V2 items.
 * <p>
 * An item represents a row in a project and can contain:
 * - ISSUE: Links to an existing Issue
 * - PULL_REQUEST: Links to an existing Pull Request
 * - DRAFT_ISSUE: A draft that hasn't been converted to a real issue yet
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Slf4j
public record GitHubProjectItemDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("node_id") @Nullable String nodeId,
        @JsonProperty("project_node_id") @Nullable String projectNodeId,
        @JsonProperty("content_type") @Nullable String contentType,
        @JsonProperty("issue_id") @Nullable Long issueId,
        @JsonProperty("issue_number") @Nullable Integer issueNumber,
        @JsonProperty("draft_title") @Nullable String draftTitle,
        @JsonProperty("draft_body") @Nullable String draftBody,
        @JsonProperty("archived") boolean archived,
        @JsonProperty("creator") @Nullable GitHubUserDTO creator,
        @JsonProperty("field_values") List<GitHubProjectFieldValueDTO> fieldValues,
        @JsonProperty("field_values_truncated") boolean fieldValuesTruncated,
        @JsonProperty("field_values_total_count") int fieldValuesTotalCount,
        @JsonProperty("field_values_end_cursor") @Nullable String fieldValuesEndCursor,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt) {
    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    /**
     * Returns a copy of this DTO with the given issue ID set.
     * <p>
     * Used by the embedded sync path where the parent issue/PR's database ID is known
     * from the enclosing query but the DTO's {@code issueId} is null because the GraphQL
     * query did not include the {@code content} block (it would be redundant).
     *
     * @param parentIssueId the GitHub database ID of the parent issue/PR
     * @return a new DTO with the issue ID set, or this DTO if issueId is already set
     */
    public GitHubProjectItemDTO withIssueId(Long parentIssueId) {
        if (this.issueId != null || parentIssueId == null) {
            return this;
        }
        return new GitHubProjectItemDTO(
                id,
                databaseId,
                nodeId,
                projectNodeId,
                contentType,
                parentIssueId,
                issueNumber,
                draftTitle,
                draftBody,
                archived,
                creator,
                fieldValues,
                fieldValuesTruncated,
                fieldValuesTotalCount,
                fieldValuesEndCursor,
                createdAt,
                updatedAt);
    }

    /**
     * Returns a copy of this DTO with the archived flag overridden.
     * <p>
     * Used by webhook handlers where GitHub sends {@code archived_at} (a timestamp)
     * but no {@code archived} boolean. The primitive {@code boolean} defaults to
     * {@code false} during JSON deserialization, so {@code processArchived()} must
     * explicitly set it to {@code true}.
     *
     * @param archived the desired archived state
     * @return a new DTO with the archived flag set
     */
    public GitHubProjectItemDTO withArchived(boolean archived) {
        if (this.archived == archived) {
            return this;
        }
        return new GitHubProjectItemDTO(
                id,
                databaseId,
                nodeId,
                projectNodeId,
                contentType,
                issueId,
                issueNumber,
                draftTitle,
                draftBody,
                archived,
                creator,
                fieldValues,
                fieldValuesTruncated,
                fieldValuesTotalCount,
                fieldValuesEndCursor,
                createdAt,
                updatedAt);
    }

    /**
     * Get the content type as an enum.
     */
    public ProjectItem.@Nullable ContentType getContentTypeEnum() {
        if (contentType == null) {
            return null;
        }
        return switch (contentType.toUpperCase(Locale.ROOT)) {
            case "ISSUE" -> ProjectItem.ContentType.ISSUE;
            case "PULL_REQUEST", "PULLREQUEST" -> ProjectItem.ContentType.PULL_REQUEST;
            case "DRAFT_ISSUE", "DRAFTISSUE" -> ProjectItem.ContentType.DRAFT_ISSUE;
            default -> null;
        };
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubProjectItemDTO from a GraphQL GHProjectV2Item model.
     *
     * @param item the GraphQL GHProjectV2Item (may be null)
     * @return GitHubProjectItemDTO or null if item is null
     */
    @Nullable
    public static GitHubProjectItemDTO fromProjectV2Item(@Nullable GHProjectV2Item item) {
        if (item == null) {
            return null;
        }

        String contentType = extractContentType(item.getType());
        Long issueId = null;
        Integer issueNumber = null;
        String draftTitle = null;
        String draftBody = null;

        GHProjectV2ItemContent content = item.getContent();
        if (content != null) {
            if (content instanceof GHIssue issue) {
                issueId = toLong(issue.getFullDatabaseId());
                issueNumber = issue.getNumber();
            } else if (content instanceof GHPullRequest pr) {
                issueId = toLong(pr.getFullDatabaseId());
                issueNumber = pr.getNumber();
            } else if (content instanceof GHDraftIssue draft) {
                draftTitle = draft.getTitle();
                draftBody = draft.getBody();
            }
        }

        // Extract field values from the GraphQL response
        GHProjectV2ItemFieldValueConnection fieldValuesConnection = item.getFieldValues();
        List<GitHubProjectFieldValueDTO> fieldValues = extractFieldValues(fieldValuesConnection);

        // Check if field values were truncated (more than the inline page size)
        // and extract cursor for follow-up pagination if needed
        boolean fieldValuesTruncated = false;
        int fieldValuesTotalCount = 0;
        String fieldValuesEndCursor = null;
        if (fieldValuesConnection != null) {
            fieldValuesTotalCount = fieldValuesConnection.getTotalCount();
            var pageInfo = fieldValuesConnection.getPageInfo();
            if (pageInfo != null) {
                fieldValuesTruncated = Boolean.TRUE.equals(pageInfo.getHasNextPage());
                fieldValuesEndCursor = pageInfo.getEndCursor();
            }
            // No overflow warning here: truncation is expected and handled — the cursor above drives
            // follow-up pagination in GitHubProjectItemFieldValueSyncService.
        }

        // Extract creator info
        GitHubUserDTO creator = GitHubUserDTO.fromActor(item.getCreator());

        return new GitHubProjectItemDTO(
                null,
                toLong(item.getFullDatabaseId()),
                item.getId(),
                null, // projectNodeId not available from GraphQL item query
                contentType,
                issueId,
                issueNumber,
                draftTitle,
                draftBody,
                item.getIsArchived(),
                creator,
                fieldValues,
                fieldValuesTruncated,
                fieldValuesTotalCount,
                fieldValuesEndCursor,
                toInstant(item.getCreatedAt()),
                toInstant(item.getUpdatedAt()));
    }

    /**
     * Extracts field values from a GraphQL field value connection.
     *
     * @param fieldValuesConnection the GraphQL field values connection (may be null)
     * @return list of field value DTOs, or empty list if no values
     */
    private static List<GitHubProjectFieldValueDTO> extractFieldValues(
            @Nullable GHProjectV2ItemFieldValueConnection fieldValuesConnection) {
        if (fieldValuesConnection == null || fieldValuesConnection.getNodes() == null) {
            return Collections.emptyList();
        }

        List<GitHubProjectFieldValueDTO> result = new ArrayList<>();
        for (GHProjectV2ItemFieldValue fieldValue : fieldValuesConnection.getNodes()) {
            GitHubProjectFieldValueDTO dto = GitHubProjectFieldValueDTO.fromFieldValue(fieldValue);
            if (dto != null && dto.fieldId() != null) {
                result.add(dto);
            }
        }
        return List.copyOf(result);
    }

    // CONVERSION HELPERS

    @Nullable
    private static Long toLong(@Nullable BigInteger value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            log.error("GitHub database ID {} exceeds Long.MAX_VALUE, truncating to {}", value, value.longValue());
            return value.longValue();
        }
    }

    @Nullable
    private static String extractContentType(@Nullable GHProjectV2ItemType type) {
        if (type == null) {
            return null;
        }
        return type.name();
    }
}
