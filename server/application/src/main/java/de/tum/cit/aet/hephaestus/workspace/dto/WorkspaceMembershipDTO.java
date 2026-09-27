package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * DTO representing a workspace membership with user information.
 */
@Schema(description = "A user's membership in a workspace")
public record WorkspaceMembershipDTO(
        @Schema(description = "Unique identifier of the user")
        Long userId,

        @Schema(description = "Login/username of the user") String userLogin,

        @Schema(description = "Display name of the user") @Nullable
        String userName,

        @Schema(description = "Role of the user in this workspace (OWNER, ADMIN, MEMBER)")
        WorkspaceRole role,

        @Schema(description = "Timestamp when the membership was created")
        Instant createdAt,

        @Schema(description = "Whether the member is left out of workspace activity")
        boolean hidden,

        @Schema(description = "Whether this linked human member can be selected for practice-review coverage")
        boolean eligibleForPracticeReview) {
    public static WorkspaceMembershipDTO from(WorkspaceMembership membership) {
        return from(membership, membership.getRole());
    }

    public static WorkspaceMembershipDTO from(WorkspaceMembership membership, WorkspaceRole effectiveRole) {
        var user = membership.getUser();
        return new WorkspaceMembershipDTO(
                user.getId(),
                user.getLogin(),
                user.getName(),
                effectiveRole,
                membership.getCreatedAt(),
                membership.isHidden(),
                membership.hasHumanUser());
    }
}
