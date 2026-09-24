package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMergeableState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestCommit;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHReviewRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHReviewRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusCheckRollup;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUserConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Domain DTO for GitHub pull requests.
 * <p>
 * This is the unified model used by both GraphQL sync and webhook handlers.
 * It can be constructed from any source (GraphQL, REST, webhook payload).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("number") int number,
        @JsonProperty("title") String title,
        @JsonProperty("body") @Nullable String body,
        @JsonProperty("state") @Nullable String state,
        @JsonProperty("html_url") @Nullable String htmlUrl,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,
        @JsonProperty("closed_at") @Nullable Instant closedAt,
        @JsonProperty("merged_at") @Nullable Instant mergedAt,
        @JsonProperty("merged_by") @Nullable GitHubUserDTO mergedBy,
        @JsonProperty("merge_commit_sha") @Nullable String mergeCommitSha,
        @JsonProperty("draft") boolean isDraft,
        @JsonProperty("merged") boolean isMerged,
        @JsonProperty("mergeable") @Nullable String mergeable,
        @JsonProperty("locked") boolean locked,
        @JsonProperty("additions") int additions,
        @JsonProperty("deletions") int deletions,
        @JsonProperty("changed_files") int changedFiles,
        @JsonProperty("commits") int commits,
        @JsonProperty("comments") int commentsCount,
        @JsonProperty("review_comments") int reviewCommentsCount,
        @JsonProperty("user") @Nullable GitHubUserDTO author,
        @JsonProperty("assignees") @Nullable List<GitHubUserDTO> assignees,
        @JsonProperty("requested_reviewers") @Nullable List<GitHubUserDTO> requestedReviewers,
        @JsonProperty("labels") @Nullable List<GitHubLabelDTO> labels,
        @JsonProperty("milestone") @Nullable GitHubMilestoneDTO milestone,
        @JsonProperty("head") @Nullable GitHubBranchRefDTO head,
        @JsonProperty("base") @Nullable GitHubBranchRefDTO base,
        @JsonProperty("repository") @Nullable GitHubRepositoryRefDTO repository,
        // GraphQL-only fields
        @Nullable ReviewDecision reviewDecision,
        @Nullable MergeStateStatus mergeStateStatus,
        @Nullable Boolean isMergeable,
        boolean maintainerCanModify,
        /**
         * Merge commit metadata extracted from GraphQL.
         * Null when the PR is not merged or when created from webhook payloads
         * (which only provide the SHA via {@link #mergeCommitSha}).
         */
        @Nullable MergeCommitInfo mergeCommitInfo,
        /**
         * What the checks said about the head, from GraphQL; null when the source did not read it (a
         * webhook payload), which leaves the stored observation alone.
         */
        @Nullable HeadChecks headChecks,
        /**
         * The numbers of the issues of this repository GitHub records the pull request as closing,
         * from GraphQL; null when the source did not read them, which leaves the stored set alone.
         */
        @Nullable List<Integer> closingIssueNumbers) {
    /** The head the checks were observed for, and their rolled-up state. */
    public record HeadChecks(String sha, CheckState state) {}

    /**
     * Merge commit metadata from GraphQL PR queries. All flat fields on the
     * Commit type — zero additional rate limit cost.
     */
    public record MergeCommitInfo(
            String sha,
            @Nullable String message,
            @Nullable String messageBody,
            @Nullable String url,
            @Nullable Instant authoredDate,
            @Nullable Instant committedDate,
            @Nullable Integer additions,
            @Nullable Integer deletions,
            @Nullable Integer changedFiles,
            @Nullable String authorLogin,
            @Nullable String committerLogin,
            @Nullable String authorEmail,
            @Nullable String committerEmail) {}

    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubPullRequestDTO from a GraphQL GHPullRequest model.
     *
     * @param pr the GraphQL GHPullRequest (may be null)
     * @return GitHubPullRequestDTO or null if pr is null
     */
    @Nullable
    public static GitHubPullRequestDTO fromPullRequest(@Nullable GHPullRequest pr) {
        if (pr == null) {
            return null;
        }

        boolean isMerged = pr.getMergedAt() != null;

        // Extract commits count from connection
        int commitsCount = 0;
        if (pr.getCommits() != null) {
            commitsCount = pr.getCommits().getTotalCount();
        }

        return new GitHubPullRequestDTO(
                null,
                toLong(pr.getFullDatabaseId()),
                pr.getId(),
                pr.getNumber(),
                pr.getTitle(),
                pr.getBody(),
                convertState(pr.getState()),
                uriToString(pr.getUrl()),
                toInstant(pr.getCreatedAt()),
                toInstant(pr.getUpdatedAt()),
                toInstant(pr.getClosedAt()),
                toInstant(pr.getMergedAt()),
                GitHubUserDTO.fromActor(pr.getMergedBy()),
                pr.getMergeCommit() != null ? pr.getMergeCommit().getOid() : null,
                pr.getIsDraft(),
                isMerged,
                null, // mergeable
                pr.getLocked(),
                pr.getAdditions(),
                pr.getDeletions(),
                pr.getChangedFiles(),
                commitsCount,
                0, // comments count
                0, // review comments count
                GitHubUserDTO.fromActor(pr.getAuthor()),
                extractAssignees(pr.getAssignees(), "PR #" + pr.getNumber()),
                extractRequestedReviewers(pr.getReviewRequests(), "PR #" + pr.getNumber()),
                GitHubLabelDTO.fromLabelConnection(pr.getLabels(), "PR #" + pr.getNumber()),
                GitHubMilestoneDTO.fromMilestone(pr.getMilestone()),
                new GitHubBranchRefDTO(pr.getHeadRefName(), pr.getHeadRefOid(), null),
                new GitHubBranchRefDTO(pr.getBaseRefName(), pr.getBaseRefOid(), null),
                null,
                // GraphQL-only fields
                convertReviewDecision(pr.getReviewDecision()),
                convertMergeStateStatus(pr.getMergeStateStatus()),
                convertMergeableState(pr.getMergeable()),
                pr.getMaintainerCanModify(),
                extractMergeCommitInfo(pr),
                extractHeadChecks(pr),
                extractClosingIssueNumbers(pr));
    }

    /**
     * The status check rollup of the head commit as one {@link CheckState}: EXPECTED and PENDING
     * are still pending, ERROR and FAILURE failed, and a head with no rollup has no checks.
     */
    @Nullable
    static HeadChecks extractHeadChecks(GHPullRequest pr) {
        if (pr.getCommits() == null || pr.getCommits().getNodes() == null) {
            return null;
        }
        for (GHPullRequestCommit node : pr.getCommits().getNodes()) {
            if (node == null || node.getCommit() == null || node.getCommit().getOid() == null) {
                continue;
            }
            GHStatusCheckRollup rollup = node.getCommit().getStatusCheckRollup();
            return new HeadChecks(node.getCommit().getOid(), toCheckState(rollup == null ? null : rollup.getState()));
        }
        return null;
    }

    static CheckState toCheckState(@Nullable GHStatusState state) {
        if (state == null) {
            return CheckState.NONE;
        }
        return switch (state) {
            case SUCCESS -> CheckState.SUCCESS;
            case ERROR, FAILURE -> CheckState.FAILURE;
            case EXPECTED, PENDING -> CheckState.PENDING;
        };
    }

    /**
     * The closing references that point at this repository's own issues. A reference into another
     * repository is dropped here: the record holds this repository's issues, and a number from
     * elsewhere would resolve to the wrong one.
     */
    @Nullable
    static List<Integer> extractClosingIssueNumbers(GHPullRequest pr) {
        if (pr.getClosingIssuesReferences() == null
                || pr.getClosingIssuesReferences().getNodes() == null) {
            return null;
        }
        String ownRepository =
                pr.getRepository() == null ? null : pr.getRepository().getNameWithOwner();
        List<Integer> numbers = new ArrayList<>();
        for (GHIssue issue : pr.getClosingIssuesReferences().getNodes()) {
            if (issue == null) continue;
            String repository =
                    issue.getRepository() == null ? null : issue.getRepository().getNameWithOwner();
            if (ownRepository == null || ownRepository.equals(repository)) {
                numbers.add(issue.getNumber());
            }
        }
        return numbers;
    }

    /**
     * Extracts merge commit metadata from a GraphQL PullRequest.
     * All fields are flat on the Commit type — zero additional rate limit cost.
     */
    @Nullable
    private static MergeCommitInfo extractMergeCommitInfo(GHPullRequest pr) {
        var mc = pr.getMergeCommit();
        if (mc == null) {
            return null;
        }

        // R1: Use messageHeadline (subject) and messageBody directly from GraphQL
        // instead of splitting the full message manually.
        // Default to empty string if null — git_commit.message is NOT NULL.
        String subject = mc.getMessageHeadline();
        if (subject == null || subject.isBlank()) {
            subject = "";
        } else {
            subject = subject.trim();
            // Truncate subject to fit varchar(1024)
            if (subject.length() > 1024) {
                subject = subject.substring(0, 1024);
            }
        }
        String body = mc.getMessageBody();
        if (body != null) {
            body = body.trim();
            if (body.isEmpty()) {
                body = null;
            }
        }

        return new MergeCommitInfo(
                mc.getOid(),
                subject,
                body,
                uriToString(mc.getUrl()),
                toInstant(mc.getAuthoredDate()),
                toInstant(mc.getCommittedDate()),
                mc.getAdditions(),
                mc.getDeletions(),
                mc.getChangedFilesIfAvailable(),
                mc.getAuthor() != null && mc.getAuthor().getUser() != null
                        ? mc.getAuthor().getUser().getLogin()
                        : null,
                mc.getCommitter() != null && mc.getCommitter().getUser() != null
                        ? mc.getCommitter().getUser().getLogin()
                        : null,
                mc.getAuthor() != null ? mc.getAuthor().getEmail() : null,
                mc.getCommitter() != null ? mc.getCommitter().getEmail() : null);
    }

    // CONVERSION HELPERS

    @Nullable
    private static ReviewDecision convertReviewDecision(@Nullable GHPullRequestReviewDecision decision) {
        if (decision == null) {
            return null;
        }
        return switch (decision) {
            case APPROVED -> ReviewDecision.APPROVED;
            case CHANGES_REQUESTED -> ReviewDecision.CHANGES_REQUESTED;
            case REVIEW_REQUIRED -> ReviewDecision.REVIEW_REQUIRED;
        };
    }

    /**
     * Maps GraphQL GHMergeStateStatus to our domain model.
     *
     * <p>Note: The DRAFT status is deprecated by GitHub but still returned in the schema.
     * We map it to BLOCKED since draft PRs are effectively blocked from merging.
     */
    @Nullable
    @SuppressWarnings("deprecation") // DRAFT is deprecated but still in the schema
    private static MergeStateStatus convertMergeStateStatus(GHMergeStateStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case BEHIND -> MergeStateStatus.BEHIND;
            case BLOCKED -> MergeStateStatus.BLOCKED;
            case CLEAN -> MergeStateStatus.CLEAN;
            case DIRTY -> MergeStateStatus.DIRTY;
            case DRAFT -> MergeStateStatus.BLOCKED; // DRAFT is deprecated, map to BLOCKED
            case HAS_HOOKS -> MergeStateStatus.HAS_HOOKS;
            case UNKNOWN -> MergeStateStatus.UNKNOWN;
            case UNSTABLE -> MergeStateStatus.UNSTABLE;
        };
    }

    @Nullable
    private static Boolean convertMergeableState(@Nullable GHMergeableState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case MERGEABLE -> true;
            case CONFLICTING -> false;
            case UNKNOWN -> null;
        };
    }

    @Nullable
    private static Long toLong(@Nullable BigInteger value) {
        if (value == null) {
            return null;
        }
        return value.longValueExact();
    }

    @Nullable
    private static String convertState(@Nullable GHPullRequestState state) {
        if (state == null) {
            return null; // Let processor handle missing state with appropriate logging
        }
        return state.name().toLowerCase();
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

    private static List<GitHubUserDTO> extractRequestedReviewers(
            @Nullable GHReviewRequestConnection connection, String context) {
        if (connection == null || connection.getNodes() == null) {
            return Collections.emptyList();
        }
        // Check overflow using the pre-filter count (total nodes fetched, including
        // Teams/Bots/Mannequins) against totalCount. The previous implementation
        // compared the post-filter count (only Users) against totalCount, producing
        // false-positive overflow warnings whenever non-User reviewers existed.
        int fetchedCount = connection.getNodes().size();
        GraphQlConnectionOverflowDetector.check(
                "requestedReviewers", fetchedCount, connection.getTotalCount(), context);
        List<GitHubUserDTO> result = connection.getNodes().stream()
                .map(GHReviewRequest::getRequestedReviewer)
                .filter(reviewer -> reviewer instanceof GHUser)
                .map(reviewer -> GitHubUserDTO.fromUser((GHUser) reviewer))
                .filter(Objects::nonNull)
                .toList();
        return result;
    }
}
