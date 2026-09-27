package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySummaryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTimelinePageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.MemberActivityDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Summaries, member lists and timelines for the Activity and Workspace activity pages. */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityQueryRepository queries;
    private final ActivityScopeResolver scopes;
    private final ActivityTimelineAssembler timelineAssembler;

    /**
     * One member's activity when {@code login} is given, otherwise the workspace's or the team's; with both, the
     * member's activity within the team's scope.
     */
    @Transactional(readOnly = true)
    public ActivitySummaryDTO summarize(
            long workspaceId, @Nullable String login, @Nullable Long teamId, ActivityRange range) {
        ActivityScope scope = scope(workspaceId, login, teamId);
        return sum(counts(scope, range), scope.actorIds());
    }

    /** Everyone shown in workspace activity, or one team's members, by name, each with their activity. */
    @Transactional(readOnly = true)
    public List<MemberActivityDTO> members(long workspaceId, @Nullable Long teamId, ActivityRange range) {
        Set<Long> teamIds = scopes.teamIds(workspaceId, teamId);
        List<User> roster = scopes.roster(workspaceId, teamIds);
        Map<Long, Map<ActivityKind, Integer>> counts =
                counts(new ActivityScope(workspaceId, ids(roster), teamIds), range);
        return roster.stream()
                .map(user -> new MemberActivityDTO(
                        Objects.requireNonNull(UserInfoDTO.fromUser(user)), sum(counts, Set.of(user.getId()))))
                .toList();
    }

    /** One page of activity, newest first, starting after the page's cursor. */
    @Transactional(readOnly = true)
    public ActivityTimelinePageDTO timeline(
            long workspaceId,
            @Nullable String login,
            @Nullable Long teamId,
            ActivityRange range,
            ActivityTimelineFilterParams page) {
        ActivityTimelineCursor after = page.position(range);
        ActivityScope scope = scope(workspaceId, login, teamId);
        if (scope.actorIds().isEmpty()) {
            return new ActivityTimelinePageDTO(List.of(), null);
        }
        Slice<ActivityEvent> events = timelineEvents(scope, range, after, page);
        List<ActivityEvent> content = events.getContent();
        return new ActivityTimelinePageDTO(
                timelineAssembler.assemble(content),
                events.hasNext() ? ActivityTimelineCursor.at(content.getLast()).encode() : null);
    }

    private ActivityScope scope(long workspaceId, @Nullable String login, @Nullable Long teamId) {
        Set<Long> teamIds = scopes.teamIds(workspaceId, teamId);
        Set<Long> actorIds = login != null
                ? Set.of(scopes.member(workspaceId, login).getId())
                : ids(scopes.roster(workspaceId, teamIds));
        return new ActivityScope(workspaceId, actorIds, teamIds);
    }

    private Slice<ActivityEvent> timelineEvents(
            ActivityScope scope, ActivityRange range, ActivityTimelineCursor after, ActivityTimelineFilterParams page) {
        return scope.inTeams()
                ? queries.findTimelineInTeams(scope, page.eventTypes(), range, after, page.pageable())
                : queries.findTimeline(scope, page.eventTypes(), range, after, page.pageable());
    }

    private Map<Long, Map<ActivityKind, Integer>> counts(ActivityScope scope, ActivityRange range) {
        if (scope.actorIds().isEmpty()) {
            return Map.of();
        }
        Set<ActivityEventType> eventTypes = ActivityKind.eventTypes(Set.of());
        var rows = scope.inTeams()
                ? queries.countByActorAndTypeInTeams(scope, eventTypes, range)
                : queries.countByActorAndType(scope, eventTypes, range);
        Map<Long, Map<ActivityKind, Integer>> counts = new HashMap<>();
        for (var row : rows) {
            ActivityKind.of(row.getEventType())
                    .ifPresent(
                            kind -> counts.computeIfAbsent(row.getActorId(), actor -> new EnumMap<>(ActivityKind.class))
                                    .merge(kind, row.getCount().intValue(), Integer::sum));
        }
        return counts;
    }

    private static ActivitySummaryDTO sum(Map<Long, Map<ActivityKind, Integer>> counts, Collection<Long> actorIds) {
        Map<ActivityKind, Integer> total = new EnumMap<>(ActivityKind.class);
        for (Long actorId : actorIds) {
            counts.getOrDefault(actorId, Map.of()).forEach((kind, count) -> total.merge(kind, count, Integer::sum));
        }
        return new ActivitySummaryDTO(
                total.getOrDefault(ActivityKind.PULL_REQUEST_OPENED, 0),
                total.getOrDefault(ActivityKind.PULL_REQUEST_MERGED, 0),
                total.getOrDefault(ActivityKind.PULL_REQUEST_CLOSED, 0),
                total.getOrDefault(ActivityKind.REVIEW_APPROVED, 0),
                total.getOrDefault(ActivityKind.REVIEW_CHANGES_REQUESTED, 0),
                total.getOrDefault(ActivityKind.REVIEW_COMMENTED, 0),
                total.getOrDefault(ActivityKind.COMMENTED, 0),
                total.getOrDefault(ActivityKind.CODE_COMMENTED, 0),
                total.getOrDefault(ActivityKind.ISSUE_OPENED, 0),
                total.getOrDefault(ActivityKind.ISSUE_CLOSED, 0));
    }

    private static Set<Long> ids(List<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toUnmodifiableSet());
    }
}
