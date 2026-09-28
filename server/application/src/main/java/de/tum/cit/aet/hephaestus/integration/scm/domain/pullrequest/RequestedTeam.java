package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * A team a GitHub pull request asks for a review, as far as Hephaestus knows the team. GitLab has no team
 * reviewers. The request goes with the team or the pull request, whichever is deleted first.
 *
 * @see <a href="https://docs.github.com/en/graphql/reference/unions#requestedreviewer">GitHub RequestedReviewer</a>
 */
@Entity
@Table(
        name = "pull_request_requested_team",
        indexes = @Index(name = "idx_pull_request_requested_team_team", columnList = "team_id"))
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class RequestedTeam {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("pullRequestId")
    @JoinColumn(
            name = "pull_request_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_pull_request_requested_team_pull_request"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamId")
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_pull_request_requested_team_team"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Team team;

    /** Both must already be stored: the pair is the row's key. */
    public RequestedTeam(PullRequest pullRequest, Team team) {
        this.id = new Id(pullRequest.getId(), team.getId());
        this.pullRequest = pullRequest;
        this.team = team;
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
        private Long teamId;
    }
}
