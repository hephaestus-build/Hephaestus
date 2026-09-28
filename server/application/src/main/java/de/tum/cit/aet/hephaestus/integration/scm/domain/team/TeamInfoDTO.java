package de.tum.cit.aet.hephaestus.integration.scm.domain.team;

import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team.Privacy;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Detailed information about a team including members, repositories, and labels")
public record TeamInfoDTO(
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
        Boolean hidden,

        @NonNull @Schema(description = "Repositories the team has access to")
        List<RepositoryInfoDTO> repositories,

        @NonNull @Schema(description = "Labels configured as filters for this team")
        List<LabelInfoDTO> labels,

        @NonNull @Schema(description = "Members of the team (excluding bots)")
        List<UserInfoDTO> members,

        @NonNull @Schema(description = "Total count of team memberships")
        Integer membershipCount,

        @NonNull @Schema(description = "Count of repository permissions")
        Integer repoPermissionCount) {
    /**
     * Creates a TeamInfoDTO from a Team entity using scope-specific settings.
     *
     * <p>This method applies scope-specific visibility and label settings,
     * enabling different configurations for the same team across multiple scopes.
     *
     * @param team the team entity
     * @param isHidden whether the team is hidden in this scope
     * @param scopeLabels labels configured as filters for this team in this scope
     * @param hiddenRepoIds repository IDs hidden from contributions in this scope
     * @param hiddenMemberIds user IDs hidden from workspace activity and team views in this workspace
     * @return the DTO with scope-specific settings applied
     */
    public static TeamInfoDTO fromTeamWithScopeSettings(
            Team team, boolean isHidden, Set<Label> scopeLabels, Set<Long> hiddenRepoIds, Set<Long> hiddenMemberIds) {
        return new TeamInfoDTO(
                team.getId(),
                team.getName(),
                team.getParentId(),
                team.getDescription(),
                team.getPrivacy(),
                team.getOrganization(),
                team.getHtmlUrl(),
                isHidden,
                team.getRepoPermissions().stream()
                        .map(permission -> RepositoryInfoDTO.fromPermissionWithScopeSettings(
                                permission,
                                hiddenRepoIds.contains(
                                        permission.getRepository().getId())))
                        .toList(),
                scopeLabels.stream().map(LabelInfoDTO::fromLabel).toList(),
                team.getMemberships().stream()
                        .map(m -> m.getUser())
                        .filter(u ->
                                u != null && !User.Type.BOT.equals(u.getType()) && !hiddenMemberIds.contains(u.getId()))
                        .map(UserInfoDTO::fromUser)
                        .toList(),
                team.getMemberships().size(),
                team.getRepoPermissions().size());
    }
}
