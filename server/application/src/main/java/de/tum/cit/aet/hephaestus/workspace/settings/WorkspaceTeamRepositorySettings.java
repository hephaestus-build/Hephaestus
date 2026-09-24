package de.tum.cit.aet.hephaestus.workspace.settings;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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

/**
 * Workspace-scoped settings for a team's repository access.
 *
 * <p>This entity stores Hephaestus-specific repository settings that are scoped to a particular
 * team within a workspace, enabling different configurations for the same team-repository
 * relationship across multiple workspaces.
 *
 * <h2>Composite Primary Key</h2>
 * Uses {@link Id} as an embedded composite key of (workspaceId, teamId, repositoryId), ensuring:
 * <ul>
 *   <li>A team-repository pair can have different settings per workspace</li>
 *   <li>Each team-repository pair has exactly one settings record per workspace</li>
 * </ul>
 *
 * <h2>Settings</h2>
 * <ul>
 *   <li><b>hiddenFromContributions:</b> Controls whether contributions from this repository
 *       should be excluded from contribution calculations for this team in this workspace</li>
 * </ul>
 *
 * @see Workspace
 * @see Team
 * @see Repository
 */
@Entity
@Table(name = "workspace_team_repository_settings")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class WorkspaceTeamRepositorySettings {

    /** Composite primary key (workspaceId, teamId, repositoryId) */
    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    /** The workspace these settings belong to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_repo_settings_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    /** The team these settings apply to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamId")
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_repo_settings_team"))
    @ToString.Exclude
    private Team team;

    /** The repository these settings apply to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("repositoryId")
    @JoinColumn(
            name = "repository_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_repo_settings_repository"))
    @ToString.Exclude
    private Repository repository;

    /**
     * Controls whether contributions from this repository should be excluded
     * from contribution calculations for this team in this workspace.
     *
     * <p>When {@code true}, pull requests, reviews, and other contributions from
     * this repository will not count toward the team's statistics in this workspace.
     */
    @Column(name = "hidden_from_contributions", nullable = false)
    private boolean hiddenFromContributions = false;

    /**
     * Creates a new WorkspaceTeamRepositorySettings for the given workspace, team, and repository.
     *
     * @param workspace the workspace these settings belong to
     * @param team the team these settings apply to
     * @param repository the repository these settings apply to
     */
    public WorkspaceTeamRepositorySettings(Workspace workspace, Team team, Repository repository) {
        this.workspace = workspace;
        this.team = team;
        this.repository = repository;
        this.id = new Id(workspace.getId(), team.getId(), repository.getId());
    }

    /**
     * Composite primary key for workspace team repository settings.
     *
     * <p>Ensures a team-repository pair can have at most one settings record per workspace.
     * All fields are populated via {@code @MapsId} from the entity relationships.
     */
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

        @Column(name = "workspace_id")
        private Long workspaceId;

        @Column(name = "team_id")
        private Long teamId;

        @Column(name = "repository_id")
        private Long repositoryId;
    }
}
