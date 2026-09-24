package de.tum.cit.aet.hephaestus.workspace.settings;

import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
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
 * Workspace-scoped team label filter configuration.
 *
 * <p>This entity associates a label with a team within a specific workspace for filtering
 * team-specific contributions. Each record represents a single label that should be used
 * to filter pull requests and issues for a team in a given workspace.
 *
 * <h2>Composite Primary Key</h2>
 * Uses {@link Id} as an embedded composite key of (workspaceId, teamId, labelId), ensuring:
 * <ul>
 *   <li>A team can have different label filters per workspace</li>
 *   <li>Each (workspace, team, label) combination is unique</li>
 *   <li>The same label can be assigned to different teams in different workspaces</li>
 * </ul>
 *
 * <h2>Purpose</h2>
 * This entity extracts the Hephaestus-specific label filtering from the Team entity,
 * enabling multi-workspace scenarios where different workspaces can have different
 * label filters for the same team.
 *
 * @see Workspace
 * @see Team
 * @see Label
 */
@Entity
@Table(name = "workspace_team_label_filter")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class WorkspaceTeamLabelFilter {

    /** Composite primary key (workspaceId, teamId, labelId) */
    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    /** The workspace this label filter belongs to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_label_filter_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    /** The team this label filter applies to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamId")
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_label_filter_team"))
    @ToString.Exclude
    private Team team;

    /** The label used for filtering */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("labelId")
    @JoinColumn(
            name = "label_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_team_label_filter_label"))
    @ToString.Exclude
    private Label label;

    /**
     * Creates a new WorkspaceTeamLabelFilter for the given workspace, team, and label.
     *
     * @param workspace the workspace this filter belongs to
     * @param team the team this filter applies to
     * @param label the label used for filtering
     */
    public WorkspaceTeamLabelFilter(Workspace workspace, Team team, Label label) {
        this.workspace = workspace;
        this.team = team;
        this.label = label;
        this.id = new Id(workspace.getId(), team.getId(), label.getId());
    }

    /**
     * Composite primary key for workspace team label filter.
     *
     * <p>Ensures a label can be associated with a team at most once per workspace.
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

        @Column(name = "label_id")
        private Long labelId;
    }
}
