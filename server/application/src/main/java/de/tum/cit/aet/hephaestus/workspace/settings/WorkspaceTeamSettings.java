package de.tum.cit.aet.hephaestus.workspace.settings;

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
 * Workspace-scoped settings for a team.
 *
 * <p>This entity stores Hephaestus-specific team settings that are scoped to a particular workspace,
 * enabling different configurations for the same team across multiple workspaces.
 *
 * <h2>Composite Primary Key</h2>
 * Uses {@link Id} as an embedded composite key of (workspaceId, teamId), ensuring:
 * <ul>
 *   <li>A team can have different settings per workspace</li>
 *   <li>Each team has exactly one settings record per workspace</li>
 * </ul>
 *
 * <h2>Settings</h2>
 * <ul>
 *   <li><b>hidden:</b> Controls whether the team is hidden in the overview/leaderboard for this workspace</li>
 * </ul>
 *
 * @see Workspace
 * @see Team
 */
@Entity
@Table(name = "workspace_team_settings")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class WorkspaceTeamSettings {

    /** Composite primary key (workspaceId, teamId) */
    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    /** The workspace these settings belong to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_settings_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    /** The team these settings apply to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamId")
    @JoinColumn(name = "team_id", nullable = false, foreignKey = @ForeignKey(name = "fk_workspace_team_settings_team"))
    @ToString.Exclude
    private Team team;

    /**
     * Controls whether the team is hidden in the overview/leaderboard for this workspace.
     *
     * <p>When {@code true}, the team will be excluded from public-facing displays
     * like leaderboards and team overviews within this workspace.
     */
    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    /**
     * Creates a new WorkspaceTeamSettings for the given workspace and team.
     *
     * @param workspace the workspace these settings belong to
     * @param team the team these settings apply to
     */
    public WorkspaceTeamSettings(Workspace workspace, Team team) {
        this.workspace = workspace;
        this.team = team;
        this.id = new Id(workspace.getId(), team.getId());
    }

    /**
     * Composite primary key for workspace team settings.
     *
     * <p>Ensures a team can have at most one settings record per workspace.
     * Both fields are populated via {@code @MapsId} from the entity relationships.
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
    }
}
