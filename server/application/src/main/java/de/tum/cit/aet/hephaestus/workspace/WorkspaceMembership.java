package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.*;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Locale;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * Join entity representing a user's membership in a workspace with role-based access control.
 *
 * <p>This entity implements the many-to-many relationship between {@link User} and {@link Workspace},
 * enriched with role information and workspace-scoped gamification data (league points).
 *
 * <h2>Composite Primary Key</h2>
 * Uses {@link Id} as an embedded composite key of (workspaceId, userId), ensuring:
 * <ul>
 *   <li>A user can belong to multiple workspaces</li>
 *   <li>A user has exactly one membership (and one role) per workspace</li>
 * </ul>
 *
 * <h2>Role Hierarchy</h2>
 * Roles follow an implicit hierarchy for authorization:
 * <pre>
 *   OWNER &gt; ADMIN &gt; MEMBER
 * </pre>
 * <ul>
 *   <li><b>OWNER:</b> Full control including workspace deletion and ownership transfer</li>
 *   <li><b>ADMIN:</b> Manage settings, members, and repositories</li>
 *   <li><b>MEMBER:</b> View access and participation in tracked activities</li>
 * </ul>
 *
 * <h2>League Points</h2>
 * The {@link #leaguePoints} field stores workspace-scoped gamification points,
 * calculated by {@link LeaguePointsRecalculator} based on user contributions.
 * Points are workspace-specific (a user has separate point totals per workspace).
 *
 * @see WorkspaceRole
 * @see WorkspaceMembershipService
 * @see WorkspaceMembershipRepository
 */
@Entity
@Table(name = "workspace_membership")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class WorkspaceMembership {

    /** Composite primary key (workspaceId, userId) */
    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    /** The workspace this membership belongs to */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_membership_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    /** The user who is a member of the workspace */
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_workspace_membership_user"))
    @ToString.Exclude
    private User user;

    /** User's role in this workspace (determines permissions) */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private WorkspaceRole role = WorkspaceRole.MEMBER;

    /**
     * Workspace-scoped gamification points.
     * Accumulated based on contributions (PRs merged, reviews, etc.).
     * Recalculated by {@link LeaguePointsRecalculator}.
     */
    @Column(name = "league_points", nullable = false)
    private int leaguePoints = 0;

    /** Whether this member is hidden from the leaderboard */
    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    /** Timestamp when this membership was created */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean hasHumanUser() {
        return user != null && user.getType() == User.Type.USER;
    }

    public @Nullable Long getUserId() {
        return user == null ? null : user.getId();
    }

    /**
     * Composite primary key for workspace membership.
     * <p>
     * Ensures a user can have at most one membership per workspace.
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

        @Nullable
        private Long workspaceId;

        @Nullable
        private Long userId;
    }

    /**
     * Role-based access control levels for workspace members.
     *
     * <h2>Permission Hierarchy</h2>
     * <pre>
     *   OWNER &gt; ADMIN &gt; MEMBER
     * </pre>
     *
     * <h2>Role Capabilities</h2>
     * <table>
     *   <tr><th>Capability</th><th>MEMBER</th><th>ADMIN</th><th>OWNER</th></tr>
     *   <tr><td>View workspace data</td><td>✓</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>Participate in activities</td><td>✓</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>Manage settings</td><td></td><td>✓</td><td>✓</td></tr>
     *   <tr><td>Manage members</td><td></td><td>✓</td><td>✓</td></tr>
     *   <tr><td>Manage repositories</td><td></td><td>✓</td><td>✓</td></tr>
     *   <tr><td>Delete workspace</td><td></td><td></td><td>✓</td></tr>
     *   <tr><td>Transfer ownership</td><td></td><td></td><td>✓</td></tr>
     * </table>
     *
     * @see de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService
     */
    public enum WorkspaceRole {
        /** Full control including deletion and ownership transfer */
        OWNER,
        /** Administrative access to settings, members, and repositories */
        ADMIN,
        /** Basic view and participation access */
        MEMBER;

        /**
         * Maps GitHub organization roles to workspace roles.
         *
         * @param organizationRole GitHub organization role (case-insensitive)
         * @return corresponding workspace role, defaults to MEMBER for unknown roles
         */
        public static WorkspaceRole fromOrganizationRole(String organizationRole) {
            if (organizationRole == null) {
                return MEMBER;
            }
            return switch (organizationRole.toUpperCase(Locale.ROOT)) {
                case "OWNER" -> OWNER;
                case "ADMIN" -> ADMIN;
                default -> MEMBER;
            };
        }
    }
}
