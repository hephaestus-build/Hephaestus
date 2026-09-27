package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsService;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves who an Activity read is about: one member, a team, or everyone shown in workspace activity. */
@Service
@RequiredArgsConstructor
class ActivityScopeResolver {

    private static final Collator NAMES = Collator.getInstance(Locale.ROOT);
    private static final Comparator<User> BY_NAME = Comparator.comparing(
                    (User user) -> user.getName() != null ? user.getName() : user.getLogin(), NAMES)
            .thenComparing(User::getLogin, NAMES);

    private final WorkspaceMembershipService memberships;
    private final WorkspaceRepository workspaces;
    private final WorkspaceTeamScopeResolver teamScopes;
    private final WorkspaceTeamSettingsService teamSettings;
    private final TeamRepository teams;

    /** A member of the workspace by login, including one hidden from workspace activity. */
    @Transactional(readOnly = true)
    public User member(long workspaceId, String login) {
        return memberships
                .findMemberByLogin(workspaceId, login)
                .orElseThrow(() -> new EntityNotFoundException("Member", login));
    }

    /**
     * The members shown in workspace activity, by name: human, not hidden, and in the team or one of its
     * sub-teams when a team is given.
     */
    @Transactional(readOnly = true)
    public List<User> roster(long workspaceId, Set<Long> teamIds) {
        Set<Long> hidden = memberships.getHiddenMemberIds(workspaceId);
        return memberships.getHumanMembersWithTeams(workspaceId).stream()
                .filter(user -> !hidden.contains(user.getId()))
                .filter(user -> teamIds.isEmpty()
                        || user.getTeamMemberships().stream()
                                .anyMatch(membership -> membership.getTeam() != null
                                        && teamIds.contains(membership.getTeam().getId())))
                .sorted(BY_NAME)
                .toList();
    }

    /**
     * The team and its sub-teams, less any sub-team hidden from workspace activity and everything below it. A
     * team outside the workspace, or one hidden itself, is not found; no team is the empty set.
     */
    @Transactional(readOnly = true)
    public Set<Long> teamIds(long workspaceId, @Nullable Long teamId) {
        if (teamId == null) {
            return Set.of();
        }
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        List<Team> workspaceTeams = teamScopes
                .resolve(workspace)
                .map(scope ->
                        teams.findAllByOrganizationIgnoreCaseAndProviderId(scope.accountLogin(), scope.providerId()))
                .orElse(List.of());
        Set<Long> hidden = teamSettings.getHiddenTeamIds(workspaceId);
        boolean visible =
                workspaceTeams.stream().anyMatch(team -> teamId.equals(team.getId())) && !hidden.contains(teamId);
        if (!visible) {
            throw new EntityNotFoundException("Team", teamId);
        }
        Map<Long, List<Long>> children = workspaceTeams.stream()
                .filter(team -> team.getParentId() != null && !hidden.contains(team.getId()))
                .collect(
                        Collectors.groupingBy(Team::getParentId, Collectors.mapping(Team::getId, Collectors.toList())));
        Set<Long> ids = new HashSet<>();
        var pending = new ArrayDeque<Long>(List.of(teamId));
        while (!pending.isEmpty()) {
            Long next = pending.pop();
            if (ids.add(next)) {
                pending.addAll(children.getOrDefault(next, List.of()));
            }
        }
        return Set.copyOf(ids);
    }
}
