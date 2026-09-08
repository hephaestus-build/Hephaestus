package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Locale;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/** One SCM actor's membership and league state in a workspace. */
@Entity
@Table(name = "workspace_membership")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class WorkspaceMembership {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_membership_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_workspace_membership_user"))
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private WorkspaceRole role = WorkspaceRole.MEMBER;

    /** Recalculated from contributions by {@link LeaguePointsRecalculator}. */
    @Column(name = "league_points", nullable = false)
    private int leaguePoints = 0;

    /** Whether this member is hidden from the leaderboard */
    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean hasHumanUser() {
        return user != null && user.getType() == User.Type.USER;
    }

    public @Nullable Long getUserId() {
        return user == null ? null : user.getId();
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

        @Nullable
        private Long workspaceId;

        @Nullable
        private Long userId;
    }

    public enum WorkspaceRole {
        OWNER,
        ADMIN,
        MEMBER;

        /** Declaration order is descending privilege: OWNER, ADMIN, MEMBER. */
        public boolean isAtLeast(WorkspaceRole requiredRole) {
            return compareTo(requiredRole) <= 0;
        }

        /** Unknown organization roles receive MEMBER access. */
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
