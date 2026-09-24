package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;

/**
 * Represents a pull request from a Git provider (e.g., GitHub).
 * <p>
 * Extends {@link Issue} using SINGLE_TABLE inheritance (discriminator: "PULL_REQUEST").
 * All PR-specific fields are nullable at the database level since Issues don't populate them.
 * <p>
 * <b>PR-specific Relationships:</b>
 * <ul>
 *   <li>{@link #mergedBy} – User who merged the PR (null if open/closed without merge)</li>
 *   <li>{@link #requestedReviewers} – Users requested to review (may not have reviewed yet)</li>
 *   <li>{@link #reviews} – Actual code review submissions</li>
 *   <li>{@link #reviewComments} – Line-level comments on the diff</li>
 *   <li>{@link #reviewThreads} – Threaded conversations on specific code ranges</li>
 * </ul>
 * <p>
 * <b>Branch Information:</b>
 * <ul>
 *   <li>{@link #headRefName}/{@link #headRefOid} – Source branch name and commit SHA</li>
 *   <li>{@link #baseRefName}/{@link #baseRefOid} – Target branch name and commit SHA</li>
 * </ul>
 *
 * @see Issue
 * @see PullRequestReview
 */
@Entity
@DiscriminatorValue(value = "PULL_REQUEST")
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class PullRequest extends Issue {

    private @Nullable Instant mergedAt;

    private boolean isDraft;

    private boolean isMerged;

    private int commits;

    private int additions;

    private int deletions;

    private int changedFiles;

    /**
     * The review decision state of the pull request.
     * Indicates whether the PR has been approved, changes requested, or review required.
     * Only available via GraphQL sync; null for webhook-only updates.
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    private ReviewDecision reviewDecision;

    /**
     * The merge state status of the pull request.
     * Indicates whether the PR can be merged based on branch status and CI checks.
     * Only available via GraphQL sync; null for webhook-only updates.
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    private MergeStateStatus mergeStateStatus;

    /**
     * Whether the pull request can be merged based on conflict status.
     * True = mergeable, False = conflicting, null = unknown/calculating.
     */
    @Nullable
    private Boolean mergeable;

    /**
     * The name of the source branch (e.g., "feature/my-feature").
     */
    private String headRefName;

    /**
     * The name of the target branch (e.g., "main").
     */
    private String baseRefName;

    /**
     * The SHA of the head commit (40 characters).
     */
    @Column(length = 40)
    private @Nullable String headRefOid;

    /**
     * The SHA of the base commit (40 characters).
     */
    @Column(length = 40)
    private String baseRefOid;

    /**
     * The SHA of the merge commit produced when this pull request was merged.
     * <p>
     * For GitLab merge requests, populated from GraphQL {@code MergeRequest.mergeCommitSha}.
     * This is the anchor used by the commit→MR linker fallback when
     * {@code commitsWithoutMergeCommits} harvest does not cover historical MRs
     * (see PR #1021 Gap 1). Null for unmerged pull requests and for GitHub
     * until its GraphQL sync is wired through to populate it.
     */
    @Nullable
    @Column(name = "merge_commit_sha", length = 40)
    private String mergeCommitSha;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_by_id")
    @ToString.Exclude
    private User mergedBy;

    @ManyToMany
    @JoinTable(
            name = "pull_request_requested_reviewers",
            joinColumns = @JoinColumn(name = "pull_request_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    @BatchSize(size = 50)
    @ToString.Exclude
    private Set<User> requestedReviewers = new HashSet<>();

    /**
     * The issues the provider records this pull request as closing — GitHub's closing references,
     * GitLab's "closes" issues — as far as they are issues of this repository. The set is the
     * provider's current statement and is replaced whole on every sync that reads it.
     */
    @ManyToMany
    @JoinTable(
            name = "pull_request_closing_issue",
            joinColumns =
                    @JoinColumn(
                            name = "pull_request_id",
                            foreignKey = @ForeignKey(name = "fk_pull_request_closing_issue_pull_request")),
            inverseJoinColumns =
                    @JoinColumn(
                            name = "issue_id",
                            foreignKey = @ForeignKey(name = "fk_pull_request_closing_issue_issue")))
    @BatchSize(size = 50)
    @ToString.Exclude
    private Set<Issue> closingIssues = new HashSet<>();

    /**
     * What the provider's checks said about the head, and for which head. Null until a sync or a
     * check event observed one; {@link #headCheckSha} names the commit the state belongs to, so a
     * state observed for an earlier head is not read as the current one.
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "head_check_state", length = 16)
    private CheckState headCheckState;

    @Nullable
    @Column(name = "head_check_sha", length = 40)
    private String headCheckSha;

    @OneToMany(mappedBy = "pullRequest", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @BatchSize(size = 50)
    @ToString.Exclude
    private Set<PullRequestReview> reviews = new HashSet<>();

    @OneToMany(mappedBy = "pullRequest", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<PullRequestReviewComment> reviewComments = new HashSet<>();

    @OneToMany(mappedBy = "pullRequest", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<PullRequestReviewThread> reviewThreads = new HashSet<>();

    @Override
    public boolean isPullRequest() {
        return true;
    }

    // Bidirectional Relationship Helpers

    /**
     * Adds a review to this pull request and maintains bidirectional consistency.
     *
     * @param review the review to add
     */
    public void addReview(PullRequestReview review) {
        if (review != null) {
            this.reviews.add(review);
            review.setPullRequest(this);
        }
    }

    /**
     * Removes a review from this pull request and maintains bidirectional consistency.
     *
     * @param review the review to remove
     */
    public void removeReview(PullRequestReview review) {
        if (review != null) {
            this.reviews.remove(review);
            review.setPullRequest(null);
        }
    }

    /**
     * Adds a review thread to this pull request and maintains bidirectional consistency.
     *
     * @param thread the thread to add
     */
    public void addReviewThread(PullRequestReviewThread thread) {
        if (thread != null) {
            this.reviewThreads.add(thread);
            thread.setPullRequest(this);
        }
    }

    /**
     * Removes a review thread from this pull request and maintains bidirectional consistency.
     *
     * @param thread the thread to remove
     */
    public void removeReviewThread(PullRequestReviewThread thread) {
        if (thread != null) {
            this.reviewThreads.remove(thread);
            thread.setPullRequest(null);
        }
    }

    /**
     * Adds a review comment to this pull request and maintains bidirectional consistency.
     * <p>
     * Note: This is a denormalized relationship - comments are also accessible via
     * reviews.*.comments or reviewThreads.*.comments. Use with care.
     *
     * @param comment the comment to add
     */
    public void addReviewComment(PullRequestReviewComment comment) {
        if (comment != null) {
            this.reviewComments.add(comment);
            comment.setPullRequest(this);
        }
    }

    /**
     * Removes a review comment from this pull request and maintains bidirectional consistency.
     *
     * @param comment the comment to remove
     */
    public void removeReviewComment(PullRequestReviewComment comment) {
        if (comment != null) {
            this.reviewComments.remove(comment);
            comment.setPullRequest(null);
        }
    }

    /**
     * Adds a requested reviewer to this pull request.
     *
     * @param reviewer the user to request review from
     */
    public void addRequestedReviewer(User reviewer) {
        if (reviewer != null) {
            this.requestedReviewers.add(reviewer);
        }
    }

    /**
     * Removes a requested reviewer from this pull request.
     *
     * @param reviewer the user to remove from requested reviewers
     */
    public void removeRequestedReviewer(User reviewer) {
        if (reviewer != null) {
            this.requestedReviewers.remove(reviewer);
        }
    }
    /**
     * Replaces the closing-issue set with the provider's current statement.
     *
     * @return whether the set changed
     */
    public boolean replaceClosingIssues(Set<Issue> issues) {
        if (this.closingIssues.equals(issues)) {
            return false;
        }
        this.closingIssues.clear();
        this.closingIssues.addAll(issues);
        return true;
    }

    /**
     * Records what the checks said about {@code sha}. A head observed for the first time takes the
     * state as given; a further observation of the same head only worsens it — one failed suite or
     * cancelled pipeline fails the head whatever the others report, and a success arriving after a
     * failure is another suite's, not the rollup's — until a sync reads the provider's own rollup,
     * which replaces the state outright.
     *
     * @return whether the observation changed anything
     */
    public boolean observeHeadChecks(String sha, CheckState state, boolean rollup) {
        CheckState next = state;
        if (!rollup && sha.equals(this.headCheckSha) && this.headCheckState != null) {
            next = worse(this.headCheckState, state);
        }
        if (sha.equals(this.headCheckSha) && next == this.headCheckState) {
            return false;
        }
        this.headCheckSha = sha;
        this.headCheckState = next;
        return true;
    }

    private static CheckState worse(CheckState recorded, CheckState observed) {
        return rank(observed) > rank(recorded) ? observed : recorded;
    }

    /** FAILURE outranks CANCELLED outranks PENDING outranks SUCCESS outranks NONE. */
    private static int rank(CheckState state) {
        return switch (state) {
            case NONE -> 0;
            case SUCCESS -> 1;
            case PENDING -> 2;
            case CANCELLED -> 3;
            case FAILURE -> 4;
        };
    }

    /*
     * Other fields intentionally not synced:
     * - MergeQueueEntry.position / MergeQueueEntry.estimatedTimeToMerge (GraphQL only)
     */
}
