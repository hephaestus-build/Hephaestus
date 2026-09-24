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

    /**
     * Removes a comment from this thread and maintains bidirectional consistency.
     * <p>
     * CRITICAL: This method must be called BEFORE deleting the comment entity
     * when orphanRemoval=true to avoid TransientObjectException.
     *
     * @param comment the comment to remove
     */
    public void removeComment(PullRequestReviewComment comment) {
        if (comment != null) {
            this.comments.remove(comment);
            comment.setThread(null);
        }
    }

    public enum State {
        RESOLVED,
        UNRESOLVED,
    }
}
