package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository.WorkGroup;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkPageDTO;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Keyset-paged work for the Activity and Workspace activity pages. */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityQueryRepository queries;
    private final ActivityScopeResolver scopes;
    private final ActivityWorkAssembler workAssembler;
    private final ActivityPeopleService people;
    private final ActivityPeopleQueryRepository peopleQueries;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityWorkPageDTO personWork(
            long workspace,
            long userId,
            ActivityPeopleRangeParams params,
            @Nullable String team,
            Set<String> repositoryKeys,
            ActivityWorkFilterParams page) {
        peopleQueries.contributor(
                workspace, userId, CurrentScmIdentityHolder.getAccountActorIds().contains(userId));
        var selected = people.select(
                workspace, team, repositoryKeys, peopleQueries.teams(workspace), peopleQueries.repositories(workspace));
        return work(
                new ActivityScope(workspace, Set.of(userId), selected.teamIds(), selected.repositoryIds()),
                page.range(params, clock, peopleQueries.earliest(selected, clock.instant())),
                page);
    }

    /**
     * One page of activity grouped by the pull request or issue it happened on, latest first, starting after the
     * page's cursor.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityWorkPageDTO work(
            long workspaceId,
            @Nullable String login,
            @Nullable String team,
            Set<String> repositoryKeys,
            ActivityPeopleRangeParams params,
            ActivityWorkFilterParams page) {
        var selected = people.select(
                workspaceId,
                team,
                repositoryKeys,
                peopleQueries.teams(workspaceId),
                peopleQueries.repositories(workspaceId));
        Set<Long> actors;
        if (login != null) {
            long userId = scopes.member(workspaceId, login).getId();
            peopleQueries.contributor(
                    workspaceId,
                    userId,
                    CurrentScmIdentityHolder.getAccountActorIds().contains(userId));
            actors = Set.of(userId);
        } else {
            actors = Set.copyOf(peopleQueries.findActorIds(selected));
        }
        return work(
                new ActivityScope(workspaceId, actors, selected.teamIds(), selected.repositoryIds()),
                page.range(params, clock, peopleQueries.earliest(selected, clock.instant())),
                page);
    }

    private ActivityWorkPageDTO work(ActivityScope scope, TimeRange range, ActivityWorkFilterParams page) {
        ActivityWorkCursor after = page.position(range);
        if (scope.actorIds().isEmpty() || scope.repositoryIds().isEmpty()) {
            return new ActivityWorkPageDTO(List.of(), null);
        }
        Set<ActivityEventType> eventTypes = page.eventTypes();
        List<WorkGroup> groups = scope.inTeams()
                ? queries.findWorkInTeams(scope, eventTypes, range, after, page.lookahead())
                : queries.findWork(scope, eventTypes, range, after, page.lookahead());
        boolean hasNext = groups.size() > page.pageSize();
        List<WorkGroup> content = hasNext ? groups.subList(0, page.pageSize()) : groups;
        return new ActivityWorkPageDTO(
                workAssembler.assemble(content),
                hasNext ? ActivityWorkCursor.after(range, content.getLast()).encode() : null);
    }
}
