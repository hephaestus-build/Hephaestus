package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves workspace membership for open work. */
@Service
@RequiredArgsConstructor
class ActivityScopeResolver {

    static final Collator NAMES = Collator.getInstance(Locale.ROOT);
    static final Comparator<User> BY_NAME = Comparator.comparing(
                    (User user) -> user.getName() != null ? user.getName() : user.getLogin(), NAMES)
            .thenComparing(User::getLogin, NAMES);

    private final WorkspaceMembershipService memberships;
    private final WorkspaceRepository workspaces;
    private final WorkspaceTeamScopeResolver teamScopes;
    private final TeamRepository teams;

    /** A member of the workspace by login, including one hidden from workspace activity. */
    @Transactional(readOnly = true)
    public User member(long workspaceId, String login) {
        return memberships
                .findMemberByLogin(workspaceId, login)
                .orElseThrow(() -> new EntityNotFoundException("Member", login));
    }

    /**
     * The workspace's teams {@code member} is in, hidden or not: hiding a team shapes workspace activity, not what
     * the member is asked to do. On GitHub a sub-team's members are its parent's members too: {@code Team.members}
     * defaults to {@code membership: ALL}, and the team sync stores what it lists.
     *
     * @see <a href="https://docs.github.com/en/graphql/reference/objects#team">GitHub Team.members</a>
     */
    @Transactional(readOnly = true)
    public List<Team> memberTeams(long workspaceId, User member) {
        Set<Long> workspaceTeamIds =
                workspaceTeams(workspaceId).stream().map(Team::getId).collect(Collectors.toSet());
        return member.getTeamMemberships().stream()
                .map(membership -> membership.getTeam())
                .filter(team -> team != null && workspaceTeamIds.contains(team.getId()))
                .toList();
    }

    private List<Team> workspaceTeams(long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        return teamScopes
                .resolve(workspace)
                .map(scope ->
                        teams.findAllByOrganizationIgnoreCaseAndProviderId(scope.accountLogin(), scope.providerId()))
                .orElse(List.of());
    }
}
