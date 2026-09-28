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
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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
     * HQL: the request {@code request} on the pull request {@code work} still awaits its reviewer's verdict. A
     * comment is not one, so a reviewer who only commented is still asked. Where GitLab stated the review, the
     * stated state decides ({@link ReviewState#isVerdict()}); GitHub lists only the reviewers it is waiting for; a
     * GitLab request stored without a state waits until the reviewer has a standing verdict.
     */
    public static final String AWAITING_REVIEW = """
            (request.reviewState NOT IN :#{T(de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer$ReviewState).verdicts()}
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
    @Setter
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
        UNAPPROVED;

        /** Whether the reviewer has not reviewed yet: not begun, begun but not submitted, or withdrawn. */
        public boolean awaitsReview() {
            return this == UNREVIEWED || this == REVIEW_STARTED || this == UNAPPROVED;
        }

        /** Whether the reviewer approved or asked for changes; a comment is not a verdict. */
        public boolean isVerdict() {
            return this == APPROVED || this == REQUESTED_CHANGES;
        }

        public static Set<ReviewState> verdicts() {
            return Arrays.stream(values()).filter(ReviewState::isVerdict).collect(Collectors.toUnmodifiableSet());
        }

        /** A state as GitLab spells it, in a webhook ({@code approved}) or in GraphQL ({@code APPROVED}). */
        public static @Nullable ReviewState of(@Nullable String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
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
