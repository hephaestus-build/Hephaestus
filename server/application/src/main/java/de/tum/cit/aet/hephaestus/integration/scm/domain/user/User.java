package de.tum.cit.aet.hephaestus.integration.scm.domain.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.BaseGitServiceEntity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Represents a Git provider user (GitHub user, bot, or organization).
 * <p>
 * Users are the actors in the git provider domain—they author issues, PRs, comments,
 * and reviews. This entity serves as a denormalized cache of user data from the Git
 * provider API, identified by the provider's database ID.
 * <p>
 * <b>Relationship Summary:</b>
 * <ul>
 *   <li>{@link #createdIssues} – Issues/PRs authored by this user</li>
 *   <li>{@link #assignedIssues} – Issues/PRs assigned to this user</li>
 *   <li>{@link #teamMemberships} – Teams this user belongs to</li>
 *   <li>{@link #repositoryCollaborations} – Repositories with direct access</li>
 *   <li>{@link #reviews}, {@link #reviewComments} – Code review activity</li>
 * </ul>
 *
 * @see de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership
 * @see de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator
 */
@Entity
// Unique constraint on LOWER(login) is managed by Liquibase (functional index — not expressible in JPA).
@Table(
        name = "\"user\"",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_user_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class User extends BaseGitServiceEntity {

    @NonNull
    private String login;

    @NonNull
    private String avatarUrl;

    @Nullable
    // Display name from Git provider; null until populated by API sync.
    // DTOs fall back to login for display when this is null.
    private String name;

    private String email;

    @NonNull
    private String htmlUrl;

    @NonNull
    @Enumerated(EnumType.STRING)
    private Type type;

    @OneToMany(mappedBy = "user")
    @ToString.Exclude
    private Set<TeamMembership> teamMemberships = new HashSet<>();

    @OneToMany(mappedBy = "user")
    @ToString.Exclude
    private Set<RepositoryCollaborator> repositoryCollaborations = new HashSet<>();

    @OneToMany(mappedBy = "author")
    @ToString.Exclude
    private Set<Issue> createdIssues = new HashSet<>();

    @ManyToMany(mappedBy = "assignees")
    @ToString.Exclude
    private Set<Issue> assignedIssues = new HashSet<>();

    @OneToMany(mappedBy = "author")
    @ToString.Exclude
    private Set<IssueComment> issueComments = new HashSet<>();

    @OneToMany(mappedBy = "author")
    @ToString.Exclude
    private Set<PullRequestReview> reviews = new HashSet<>();

    @OneToMany(mappedBy = "author")
    @ToString.Exclude
    private Set<PullRequestReviewComment> reviewComments = new HashSet<>();

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES)
    public enum Type {
        USER,
        ORGANIZATION,
        BOT;

        @JsonCreator
        public static @Nullable Type fromString(@Nullable String value) {
            if (value == null) {
                return null;
            }
            return Type.valueOf(value.toUpperCase());
        }

        @JsonValue
        public String toValue() {
            return name();
        }
    }

    public void addTeam(Team team) {
        teamMemberships.add(new TeamMembership(team, this, TeamMembership.Role.MEMBER));
    }

    public void removeTeam(Team team) {
        teamMemberships.removeIf(m -> m.getTeam() != null && m.getTeam().equals(team));
    }
}
