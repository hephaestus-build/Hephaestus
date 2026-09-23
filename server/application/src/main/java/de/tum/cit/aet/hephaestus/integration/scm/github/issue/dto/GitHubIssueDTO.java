package de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUserConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.SubIssuesSummaryDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Domain DTO for GitHub issues.
 * <p>
 * This is the unified model used by both GraphQL sync and webhook handlers.
 * It can be constructed from any source (GraphQL, REST, webhook payload).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("node_id") @Nullable String nodeId,
        @JsonProperty("number") int number,
        @JsonProperty("title") @Nullable String title,
        @JsonProperty("body") @Nullable String body,
        @JsonProperty("state") @Nullable String state,
        @JsonProperty("state_reason") @Nullable String stateReason,
        @JsonProperty("html_url") @Nullable String htmlUrl,
        @JsonProperty("comments") int commentsCount,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,
        @JsonProperty("closed_at") @Nullable Instant closedAt,
        @JsonProperty("locked") boolean locked,
        @JsonProperty("user") @Nullable GitHubUserDTO author,
        @JsonProperty("assignees") @Nullable List<GitHubUserDTO> assignees,
        @JsonProperty("labels") @Nullable List<GitHubLabelDTO> labels,
        @JsonProperty("milestone") @Nullable GitHubMilestoneDTO milestone,
        @JsonProperty("type") @Nullable GitHubIssueTypeDTO issueType,
        @JsonProperty("repository") @Nullable GitHubRepositoryRefDTO repository,
        @JsonProperty("pull_request") @Nullable PullRequestRef pullRequest,
        /** The provider's rollup over this issue's sub-issues, as a webhook payload carries it. */
        @JsonProperty("sub_issues_summary") @Nullable SubIssuesSummaryDTO subIssuesSummary) {
    /**
     * Returns true if this issue is actually a pull request.
     * GitHub REST API includes a pull_request field when the issue is a PR.
     */
    public boolean isPullRequest() {
        return pullRequest != null;
    }

    /**
     * Minimal reference to indicate this issue is a pull request.
     * GitHub REST API includes this field in issue webhooks when the issue is actually a PR.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestRef(
            @JsonProperty("url") String url,
            @JsonProperty("html_url") String htmlUrl) {}

    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubIssueDTO from a GraphQL GHIssue model.
     * <p>
     * The GraphQL query must include all required fields (title, url, etc.)
     * for this method to work correctly. If fields are missing, the processor
     * will fail with a validation error.
     *
     * @param issue the GraphQL GHIssue (may be null)
     * @return GitHubIssueDTO or null if issue is null
     */
    @Nullable
    public static GitHubIssueDTO fromIssue(@Nullable GHIssue issue) {
        if (issue == null) {
            return null;
        }

        return new GitHubIssueDTO(
                null,
                toLong(issue.getFullDatabaseId()),
                issue.getId(),
                issue.getNumber(),
                issue.getTitle(),
                issue.getBody(),
                convertState(issue.getState()),
                convertStateReason(issue.getStateReason()),
                uriToString(issue.getUrl()),
                issue.getComments() != null ? issue.getComments().getTotalCount() : 0,
                toInstant(issue.getCreatedAt()),
                toInstant(issue.getUpdatedAt()),
                toInstant(issue.getClosedAt()),
                Boolean.TRUE.equals(issue.getLocked()),
                GitHubUserDTO.fromActor(issue.getAuthor()),
                extractAssignees(issue.getAssignees(), "Issue #" + issue.getNumber()),
                GitHubLabelDTO.fromLabelConnection(issue.getLabels(), "Issue #" + issue.getNumber()),
                GitHubMilestoneDTO.fromMilestone(issue.getMilestone()),
                GitHubIssueTypeDTO.fromIssueType(issue.getIssueType()),
                null, // repository
                null, // pullRequest - GraphQL issues are never PRs
                null // subIssuesSummary - the GraphQL sub-issue sync carries it
                );
    }

    /**
     * Creates a GitHubIssueDTO from a GraphQL GHIssue model that includes repository reference.
     * <p>
     * Used for blocker/parent issues in dependency/sub-issue sync where the referenced
     * issue may belong to a different repository. The repository reference is needed
     * to create stub issues in the correct repository.
     * <p>
     * The GraphQL query must include all required fields (title, url, repository, etc.)
     * for this method to work correctly.
     *
     * @param issue the GraphQL GHIssue with repository field populated (may be null)
     * @return GitHubIssueDTO with repository reference, or null if issue is null
     */
    @Nullable
    public static GitHubIssueDTO fromIssueWithRepository(@Nullable GHIssue issue) {
        if (issue == null) {
            return null;
        }

        return new GitHubIssueDTO(
                null,
                toLong(issue.getFullDatabaseId()),
                issue.getId(),
                issue.getNumber(),
                issue.getTitle(),
                issue.getBody(),
                convertState(issue.getState()),
                convertStateReason(issue.getStateReason()),
                uriToString(issue.getUrl()),
                issue.getComments() != null ? issue.getComments().getTotalCount() : 0,
                toInstant(issue.getCreatedAt()),
                toInstant(issue.getUpdatedAt()),
                toInstant(issue.getClosedAt()),
                Boolean.TRUE.equals(issue.getLocked()),
                GitHubUserDTO.fromActor(issue.getAuthor()),
                extractAssignees(issue.getAssignees(), "Issue #" + issue.getNumber()),
                GitHubLabelDTO.fromLabelConnection(issue.getLabels(), "Issue #" + issue.getNumber()),
                GitHubMilestoneDTO.fromMilestone(issue.getMilestone()),
                GitHubIssueTypeDTO.fromIssueType(issue.getIssueType()),
                GitHubRepositoryRefDTO.fromRepository(issue.getRepository()),
                null, // pullRequest - GraphQL issues are never PRs
                null // subIssuesSummary - the GraphQL sub-issue sync carries it
                );
    }

    // CONVERSION HELPERS

    @Nullable
    private static Long toLong(@Nullable BigInteger value) {
        if (value == null) {
            return null;
        }
        return value.longValueExact();
    }

    @Nullable
    private static String convertState(@Nullable GHIssueState state) {
        if (state == null) {
            return null; // Let processor handle missing state with appropriate logging
        }
        return state.name().toLowerCase();
    }

    @Nullable
    private static String convertStateReason(@Nullable GHIssueStateReason stateReason) {
        if (stateReason == null) {
            return null;
        }
        return stateReason.name().toLowerCase();
    }

    private static List<GitHubUserDTO> extractAssignees(@Nullable GHUserConnection connection, String context) {
        if (connection == null || connection.getNodes() == null) {
            return Collections.emptyList();
        }
        List<GitHubUserDTO> result = connection.getNodes().stream()
                .map(GitHubUserDTO::fromUser)
                .filter(Objects::nonNull)
                .toList();
        GraphQlConnectionOverflowDetector.check("assignees", result.size(), connection.getTotalCount(), context);
        return result;
    }
}
