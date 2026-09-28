package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Someone the provider lists as a reviewer of a pull request, and, where the provider says, where their review
 * stands. GitHub lists only the reviewers it is waiting for and says nothing more, so its rows carry no state;
 * GitLab keeps every reviewer listed and states each one's review.
 */
@Entity
@Table(name = "pull_request_requested_reviewers")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class RequestedReviewer {

    /**
     * HQL: the request {@code request} on the pull request {@code work} still awaits its reviewer's verdict, an
     * approval or a request for changes. A comment is not one, so a reviewer who only commented is still asked.
     * Where GitLab stated the review, the stated state decides; GitHub lists only the reviewers it is waiting for; a
     * GitLab request stored without a state waits until the reviewer has a standing verdict. The review's
     * {@code pullRequest.deletedAt} check repeats {@code work}'s because {@code MirrorQueryPredicateGuardTest} requires
     * it on every review a query reads.
     */
    public static final String AWAITING_REVIEW = """
            (request.reviewState NOT IN (
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer$ReviewState.APPROVED,
                de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer$ReviewState.REQUESTED_CHANGES
            )
            OR (request.reviewState IS NULL AND (
                work.provider.type <> de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType.GITLAB
                OR NOT EXISTS (
                    SELECT 1 FROM PullRequestReview review
                    WHERE review.pullRequest = work
                    AND review.pullRequest.deletedAt IS NULL
                    AND review.author = request.user
                    AND
            """ + PullRequestReview.VERDICT + """
                ))))
            """;

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("pullRequestId")
    @JoinColumn(
            name = "pull_request_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk6dld06xx8rh9xhqfnca070a0i"))
    @ToString.Exclude
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fkioq4g5aksr97l6qyl4g5l63tn"))
    @ToString.Exclude
    private User user;

    /** The provider's statement of this reviewer's review; null where the provider makes none. */
    @Setter(AccessLevel.PACKAGE)
    @Enumerated(EnumType.STRING)
    @Column(name = "review_state", length = 32)
    private @Nullable ReviewState reviewState;

    /** Both must already be stored: the pair is the row's key. */
    public RequestedReviewer(PullRequest pullRequest, User user, @Nullable ReviewState reviewState) {
        this.id = new Id(pullRequest.getId(), user.getId());
        this.pullRequest = pullRequest;
        this.user = user;
        this.reviewState = reviewState;
    }

    /**
     * GitLab's reviewer states. A reviewer asked again goes back to {@link #UNREVIEWED}.
     *
     * @see <a href="https://docs.gitlab.com/user/project/integrations/webhook_events/#merge-request-events">GitLab
     *     merge request events: reviewers</a>
     */
    public enum ReviewState {
        UNREVIEWED,
        REVIEW_STARTED,
        REVIEWED,
        REQUESTED_CHANGES,
        APPROVED,
        UNAPPROVED
    }

    /** Fields are populated by {@code @MapsId} from the entity relationships. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Nullable
        private Long pullRequestId;

        @Nullable
        private Long userId;
    }
}
