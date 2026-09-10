package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * SCM contributor information for leaderboards and practice-review selection; not account access.
 */
@Schema(description = "An SCM contributor in a workspace")
public record WorkspaceContributorDTO(
        @Schema(description = "Unique identifier of the user")
        Long userId,

        @Schema(description = "Login/username of the user") String userLogin,

        @Schema(description = "Display name of the user") @Nullable
        String userName,

        @Schema(description = "League points earned by the user in this workspace", example = "150")
        int leaguePoints,

        @Schema(description = "Timestamp when the membership was created")
        Instant createdAt,

        @Schema(description = "Whether the member is hidden from the leaderboard")
        boolean hidden,

        @Schema(description = "Whether this linked human member can be selected for practice-review coverage")
        boolean eligibleForPracticeReview) {
    public static WorkspaceContributorDTO from(WorkspaceMembership membership) {
        var user = membership.getUser();
        return new WorkspaceContributorDTO(
                user.getId(),
                user.getLogin(),
                user.getName(),
                membership.getLeaguePoints(),
                membership.getCreatedAt(),
                membership.isHidden(),
                membership.hasHumanUser());
    }
}
