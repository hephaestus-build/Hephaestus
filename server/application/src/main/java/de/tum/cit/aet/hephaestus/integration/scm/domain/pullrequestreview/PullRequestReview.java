package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "pull_request_review",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_pr_review_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class PullRequestReview extends BaseGitServiceEntity {

    /** HQL: the review {@code review} is a verdict that still stands: an approval or a request for changes. */
    public static final String VERDICT = """
            review.isDismissed = false
            AND review.state IN (
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview$State.APPROVED,
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview$State.CHANGES_REQUESTED
            )
            """;

    /** HQL: the review {@code review} still stands: submitted with a verdict or a comment, and not dismissed. */
    public static final String STANDING = """
            review.isDismissed = false
            AND review.state IN (
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview$State.APPROVED,
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview$State.CHANGES_REQUESTED,
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview$State.COMMENTED
            )
            """;

    @Column(columnDefinition = "TEXT")
    private String body;

    // We handle dismissed in a separate field to keep the original state
    @NonNull
    @Enumerated(EnumType.STRING)
    private State state;

    private boolean isDismissed;

    @NonNull
    private String htmlUrl;

    @NonNull
    private Instant submittedAt;

    private @Nullable String commitId;

    private @Nullable Boolean authorCanPushToRepository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    @ToString.Exclude
    private @Nullable User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pull_request_id")
    @ToString.Exclude
    @Nullable
    private PullRequest pullRequest;

    @OneToMany(mappedBy = "review", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<PullRequestReviewComment> comments = new HashSet<>();

    // Bidirectional Relationship Helpers

    /**
     * Adds a comment to this review and maintains bidirectional consistency.
     *
     * @param comment the comment to add
     */
    public void addComment(PullRequestReviewComment comment) {
        if (comment != null) {
            this.comments.add(comment);
            comment.setReview(this);
        }
    }

    /**
     * Removes a comment from this review and maintains bidirectional consistency.
     * <p>
     * CRITICAL: This method must be called BEFORE deleting the comment entity
     * when orphanRemoval=true to avoid TransientObjectException.
     *
     * @param comment the comment to remove
     */
    public void removeComment(PullRequestReviewComment comment) {
        if (comment != null) {
            this.comments.remove(comment);
            comment.setReview(null);
        }
    }

    /**
     * Review state. Maps directly to GitHub GraphQL PullRequestReviewState enum.
     * <p>
     * Note: The {@code isDismissed} field provides additional context for dismissed
     * reviews while preserving the original review state (e.g., an APPROVED review
     * that was later dismissed).
     *
     * @see <a href="https://docs.github.com/en/graphql/reference/enums#pullrequestreviewstate">GitHub PullRequestReviewState</a>
     */
    public enum State {
        /** Review with comments only, no approval/rejection. */
        COMMENTED,
        /** Review approving the changes. */
        APPROVED,
        /** Review requesting changes before merge. */
        CHANGES_REQUESTED,
        /** Review is pending and not yet submitted (only visible to the author). */
        PENDING,
        /** Review was dismissed by a maintainer. */
        DISMISSED,
        /** Unknown or unmapped state (fallback for forward compatibility). */
        UNKNOWN,
    }
}
