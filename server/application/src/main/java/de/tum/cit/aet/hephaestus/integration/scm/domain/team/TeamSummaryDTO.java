package de.tum.cit.aet.hephaestus.integration.scm.domain.team;

import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team.Privacy;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A lightweight DTO for team data that only contains basic information.
 * This is used in contexts where full team details (repositories, labels, members) are not needed,
 * avoiding expensive JOINs and lazy loading issues.
 */
@Schema(description = "Lightweight summary of a team without member/repository details")
public record TeamSummaryDTO(
        @NonNull @Schema(description = "Unique identifier of the team")
        Long id,

        @NonNull @Schema(description = "Name of the team") String name,

        @Nullable @Schema(description = "ID of the parent team, if this is a sub-team")
        Long parentId,

        @Schema(description = "Description of the team") String description,

        @Schema(description = "Privacy level of the team (SECRET or VISIBLE)")
        Privacy privacy,

        @Schema(description = "Organization the team belongs to")
        String organization,

        @Schema(description = "URL to the team's page on the git provider")
        String htmlUrl,

        @NonNull @Schema(description = "Whether the team is hidden from workspace activity")
        Boolean hidden) {
    /**
     * Creates a TeamSummaryDTO from a Team entity using scope-specific settings.
     *
     * <p>This method applies scope-specific visibility settings,
     * enabling different configurations for the same team across multiple scopes.
     *
     * @param team the team entity
     * @param isHidden whether the team is hidden in this scope
     * @return the DTO with scope-specific settings applied
     */
    public static TeamSummaryDTO fromTeamWithScopeSettings(Team team, boolean isHidden) {
        return new TeamSummaryDTO(
                team.getId(),
                team.getName(),
                team.getParentId(),
                team.getDescription(),
                team.getPrivacy(),
                team.getOrganization(),
                team.getHtmlUrl(),
                isHidden);
    }
}
