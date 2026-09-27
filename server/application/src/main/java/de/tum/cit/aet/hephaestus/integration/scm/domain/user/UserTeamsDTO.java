package de.tum.cit.aet.hephaestus.integration.scm.domain.user;

import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamSummaryDTO;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

public record UserTeamsDTO(
        @NonNull Long id,
        @NonNull String login,
        String email,
        @NonNull String name,
        @NonNull String url,
        @NonNull Set<TeamSummaryDTO> teams,
        boolean hidden) {
    /**
     * Creates a UserTeamsDTO from a User entity using scope-specific settings.
     *
     * <p>{@code inScope} is mandatory and load-bearing: a {@link User} is global, so its team
     * memberships span every tenant it belongs to. Without this filter the caller's tenant would
     * receive the user's teams from OTHER tenants (including private ones). {@code hiddenTeamIds} is a
     * per-scope display setting and is NOT a tenancy boundary — it cannot substitute for {@code inScope}.
     *
     * @param user the user entity
     * @param hiddenTeamIds set of team IDs that are hidden in this scope
     * @param hidden whether this member is hidden from workspace activity
     * @param inScope predicate selecting the teams that belong to the caller's tenant
     * @return the DTO with scope-specific settings applied
     */
    public static UserTeamsDTO fromUserWithScopeSettings(
            User user, Set<Long> hiddenTeamIds, boolean hidden, Predicate<Team> inScope) {
        return new UserTeamsDTO(
                user.getId(),
                user.getLogin(),
                user.getEmail(),
                user.getName() != null ? user.getName() : user.getLogin(),
                user.getHtmlUrl(),
                user.getTeamMemberships().stream()
                        .map(m -> m.getTeam())
                        .filter(t -> t != null)
                        .filter(inScope)
                        .map(team ->
                                TeamSummaryDTO.fromTeamWithScopeSettings(team, hiddenTeamIds.contains(team.getId())))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                hidden);
    }
}
