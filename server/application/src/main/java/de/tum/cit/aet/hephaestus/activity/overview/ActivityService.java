package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository.BucketCount;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository.WorkGroup;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityBucketDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityOverviewDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySummaryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkPageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.MemberActivityDTO;
import de.tum.cit.aet.hephaestus.core.time.TimeBuckets;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Summaries, member lists and work lists for the Activity and Workspace activity pages. */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityQueryRepository queries;
    private final ActivityScopeResolver scopes;
    private final ActivityWorkAssembler workAssembler;

    /**
     * One member's activity when {@code login} is given, otherwise the workspace's or the team's; with both, the
     * member's activity within the team's scope. In total, and in each bucket.
     */
    @Transactional(readOnly = true)
    public ActivityOverviewDTO overview(
            long workspaceId, @Nullable String login, @Nullable Long teamId, TimeBuckets buckets) {
        ActivityScope scope = scope(workspaceId, login, teamId);
        List<Map<ActivityKind, Integer>> counts = buckets.starts().stream()
                .<Map<ActivityKind, Integer>>map(start -> new EnumMap<>(ActivityKind.class))
                .toList();
        Map<ActivityKind, Integer> total = new EnumMap<>(ActivityKind.class);
        for (BucketCount row : bucketCounts(scope, buckets)) {
            ActivityKind.of(row.getEventType()).ifPresent(kind -> {
                int count = row.getCount().intValue();
                counts.get(row.getBucket() - 1).merge(kind, count, Integer::sum);
                total.merge(kind, count, Integer::sum);
            });
        }
        return new ActivityOverviewDTO(
                summaryOf(total),
                buckets.size(),
                IntStream.range(0, counts.size())
                        .mapToObj(i -> new ActivityBucketDTO(buckets.starts().get(i), summaryOf(counts.get(i))))
                        .toList());
    }

    /** Everyone shown in workspace activity, or one team's members, by name, each with their activity. */
    @Transactional(readOnly = true)
    public List<MemberActivityDTO> members(long workspaceId, @Nullable Long teamId, TimeRange range) {
        Set<Long> teamIds = scopes.teamIds(workspaceId, teamId);
        List<User> roster = scopes.roster(workspaceId, teamIds);
        Map<Long, Map<ActivityKind, Integer>> counts =
                counts(new ActivityScope(workspaceId, ids(roster), teamIds), range);
        return roster.stream()
                .map(user -> new MemberActivityDTO(
                        Objects.requireNonNull(UserInfoDTO.fromUser(user)),
                        summaryOf(counts.getOrDefault(user.getId(), Map.of()))))
                .toList();
    }

    /**
     * One page of activity grouped by the pull request or issue it happened on, latest first, starting after the
     * page's cursor.
     */
    @Transactional(readOnly = true)
    public ActivityWorkPageDTO work(
            long workspaceId,
            @Nullable String login,
            @Nullable Long teamId,
            TimeRange range,
            ActivityWorkFilterParams page) {
        ActivityWorkCursor after = page.position(range);
        ActivityScope scope = scope(workspaceId, login, teamId);
        if (scope.actorIds().isEmpty()) {
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

    private ActivityScope scope(long workspaceId, @Nullable String login, @Nullable Long teamId) {
        Set<Long> teamIds = scopes.teamIds(workspaceId, teamId);
        Set<Long> actorIds = login != null
                ? Set.of(scopes.member(workspaceId, login).getId())
                : ids(scopes.roster(workspaceId, teamIds));
        return new ActivityScope(workspaceId, actorIds, teamIds);
    }

    private List<BucketCount> bucketCounts(ActivityScope scope, TimeBuckets buckets) {
        if (scope.actorIds().isEmpty() || buckets.starts().isEmpty()) {
            return List.of();
        }
        Set<ActivityEventType> eventTypes = ActivityKind.eventTypes(Set.of());
        Long[] starts = buckets.epochSeconds();
        return scope.inTeams()
                ? queries.countByBucketAndTypeInTeams(scope, eventTypes, buckets.range(), starts)
                : queries.countByBucketAndType(scope, eventTypes, buckets.range(), starts);
    }

    private Map<Long, Map<ActivityKind, Integer>> counts(ActivityScope scope, TimeRange range) {
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

    private static ActivitySummaryDTO summaryOf(Map<ActivityKind, Integer> total) {
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
