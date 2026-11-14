package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.BaseGitServiceEntity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "pull_request_review_thread",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_pr_review_thread_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class PullRequestReviewThread extends BaseGitServiceEntity {

    /**
     * GitHub GraphQL node ID for the thread.
     * <p>
     * Maps to the {@code id} field from GitHub's PullRequestReviewThread GraphQL type.
     * Used for GraphQL operations that require the global node ID.
     */
    @Column(length = 128)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private State state = State.UNRESOLVED;

    @Column(columnDefinition = "TEXT")
    private @Nullable String path;

    private @Nullable Integer line;

    private @Nullable Integer startLine;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PullRequestReviewComment.@Nullable Side side;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PullRequestReviewComment.@Nullable Side startSide;

    /** SHA of the head commit the thread's root note anchors to (GitLab: {@code diffRefs.headSha}). */
    @Column(name = "commit_sha", length = 64)
    private @Nullable String commitSha;

    /**
     * SHA of the base commit the thread's root note anchors to (GitLab: {@code diffRefs.baseSha},
     * falling back to {@code startSha} when the base is unavailable).
     */
    @Column(name = "original_commit_sha", length = 64)
    private @Nullable String originalCommitSha;

    private @Nullable Boolean outdated;

    private @Nullable Boolean collapsed;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "root_comment_id")
    @ToString.Exclude
    private PullRequestReviewComment rootComment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id")
    @ToString.Exclude
    @Nullable
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    @ToString.Exclude
    private @Nullable User resolvedBy;

    /**
     * When the thread was resolved. GitLab states it on the discussion, so its sync fills it; GitHub's
     * GraphQL thread carries no resolution time, so on GitHub it is the time of the
     * {@code pull_request_review_thread} resolved event and stays null for a resolution only the sync
     * saw. Cleared when the thread is unresolved again.
     */
    @Nullable
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * The date of the last resolve or unresolve event that changed this thread on GitHub. A later-arriving event with
     * an older date changes nothing. Null until such an event arrives, and on GitLab, where a whole read of the merge
     * request's discussions orders the resolution ({@code PullRequest.discussionsObservedAt}).
     */
    @Nullable
    @Column(name = "resolution_changed_at")
    private Instant resolutionChangedAt;

    @OneToMany(mappedBy = "thread", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<PullRequestReviewComment> comments = new HashSet<>();

    // Bidirectional Relationship Helpers

    /**
     * Adds a comment to this thread and maintains bidirectional consistency.
     *
     * @param comment the comment to add
     */
    public void addComment(PullRequestReviewComment comment) {
        if (comment != null) {
            this.comments.add(comment);
            comment.setThread(this);
        }
    }

    public enum State {
        RESOLVED,
        UNRESOLVED,
    }
}
