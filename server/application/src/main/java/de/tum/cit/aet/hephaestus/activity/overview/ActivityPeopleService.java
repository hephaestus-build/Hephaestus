package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityActionDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityBreakdownDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityContributorKind;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityFacetsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityHighlightsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySparklineWeekDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTeamDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWeekDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO.PublicActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityWorkDTO.PublicActivityWorkSummaryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO.WorkItemType;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ActivityPeopleService {
    private final ActivityPeopleQueryRepository queries;
    private final Clock clock;
    private final PublicRepositoryVisibility visibility;
    private final PublicActivityWorkQueryRepository publicWork;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPeopleDTO people(
            long workspace, ActivityPeopleRangeParams params, @Nullable String team, Set<String> repositoryKeys) {
        var teams = queries.teams(workspace);
        var repositories = queries.repositories(workspace);
        var scope = select(workspace, team, repositoryKeys, teams, repositories);
        return aggregate(scope, params, teams, repositories);
    }

    /** What {@link #people} lists to narrow by, without counting anyone. No period or scope changes it. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityFacetsDTO facets(long workspace) {
        return new ActivityFacetsDTO(queries.repositories(workspace), queries.teams(workspace));
    }

    /** The public people, and how many contributors without an account the page counts instead of naming. */
    public record PublicPeople(ActivityPeopleDTO people, int unlistedContributors) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PublicPeople publicPeople(
            long workspace,
            ActivityPeopleRangeParams params,
            Set<String> repositoryKeys,
            boolean contributorsWithoutAccount) {
        var repositories = queries.publicRepositories(workspace, visibility.confirmedAfter());
        var selected = select(workspace, null, repositoryKeys, List.of(), repositories);
        var scope = ActivityScope.published(
                workspace, selected.repositoryIds(), withoutAccount(contributorsWithoutAccount));
        var people = aggregate(scope, params, List.of(), repositories);
        if (contributorsWithoutAccount) return new PublicPeople(people, 0);
        long unlisted = queries.countPublicContributors(
                scope.withoutAccount(ActivityScope.WithoutAccount.ONLY),
                new TimeRange(people.from(), people.to()),
                0,
                false);
        return new PublicPeople(people, Math.toIntExact(unlisted));
    }

    private static ActivityScope.WithoutAccount withoutAccount(boolean shown) {
        return shown ? ActivityScope.WithoutAccount.SHOWN : ActivityScope.WithoutAccount.HIDDEN;
    }

    private ActivityPeopleDTO aggregate(
            ActivityScope scope,
            ActivityPeopleRangeParams params,
            List<ActivityTeamDTO> teams,
            List<ActivityRepositoryDTO> repositories) {
        var history = history(scope);
        var range = params.resolve(clock, () -> queries.findEarliest(history));
        var rows = queries.findPeople(scope, range, 0, false);
        Map<Long, ActivityPeopleQueryRepository.CountRow> totals = new LinkedHashMap<>();
        Map<Long, List<ActivitySparklineWeekDTO>> weeks = new LinkedHashMap<>();
        for (var row : rows) {
            long person = row.getActorId();
            var week = row.getWeek();
            if (row.getTotal() == 1) {
                totals.put(person, row);
            } else if (week != null) {
                weeks.computeIfAbsent(person, key -> new ArrayList<>())
                        .add(new ActivitySparklineWeekDTO(week, row.getOpened() + row.getReviewed() + row.getIssues()));
            }
        }
        List<ActivityPersonDTO> all = totals.values().stream()
                .map(row -> new ActivityPersonDTO(
                        row.person(),
                        row.kind(),
                        row.counts(),
                        row.getFirstContribution(),
                        List.copyOf(weeks.getOrDefault(row.getActorId(), List.of()))))
                .toList();
        var people = all.stream()
                .filter(row -> row.kind() == ActivityContributorKind.PERSON)
                .toList();
        var coverage = queries.coverage(history);
        boolean firstKnown = coverage.totalRepositories() > 0
                && coverage.completeRepositories() == coverage.totalRepositories()
                && coverage.since() != null
                && range.from().isAfter(coverage.since());
        var highlights = new ActivityHighlightsDTO(people.stream()
                .filter(row -> firstKnown
                        && row.firstContributionAt() != null
                        && !row.firstContributionAt().isBefore(range.from())
                        && row.firstContributionAt().isBefore(range.to()))
                .map(row -> row.person().id())
                .toList());
        return new ActivityPeopleDTO(
                range.from(),
                range.to(),
                people,
                all.stream()
                        .filter(row -> row.kind() != ActivityContributorKind.PERSON)
                        .toList(),
                coverage,
                highlights,
                repositories,
                teams);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPersonDetailDTO person(
            long workspace,
            long userId,
            ActivityPeopleRangeParams params,
            @Nullable String team,
            Set<String> repositoryKeys) {
        boolean own = CurrentScmIdentityHolder.getAccountActorIds().contains(userId);
        var contributor = queries.contributor(workspace, userId, own);
        var repositories = queries.repositories(workspace);
        var scope = select(workspace, team, repositoryKeys, queries.teams(workspace), repositories);
        var range = params.resolve(clock, () -> queries.findEarliest(scope));
        return detail(scope, range, userId, own, contributor, repositories);
    }

    private ActivityPersonDetailDTO detail(
            ActivityScope scope,
            TimeRange range,
            long userId,
            boolean own,
            ActivityPeopleQueryRepository.Contributor contributor,
            List<ActivityRepositoryDTO> repositories) {
        var rows = queries.people(scope, range, userId, own);
        var total = rows.stream()
                .filter(ActivityPeopleQueryRepository.PersonCount::total)
                .findFirst()
                .orElseGet(() -> new ActivityPeopleQueryRepository.PersonCount(
                        contributor.person(),
                        contributor.kind(),
                        true,
                        null,
                        null,
                        null,
                        new ActivityCountsDTO(0, 0, 0, 0, 0, 0, 0, 0),
                        new ActivityBreakdownDTO(0, 0, 0, 0, 0, 0, 0)));
        var weeks = rows.stream()
                .filter(row -> row.week() != null)
                .map(row -> new ActivityWeekDTO(Objects.requireNonNull(row.week()), row.counts(), row.breakdown()))
                .toList();
        var byRepository = rows.stream()
                .filter(row -> row.repository() != null)
                .map(row -> new ActivityRepositoryCountsDTO(
                        repositories.stream()
                                .filter(repo -> repo.id() == Objects.requireNonNull(row.repository()))
                                .findFirst()
                                .orElseThrow(),
                        row.counts(),
                        row.breakdown()))
                .toList();
        return new ActivityPersonDetailDTO(
                range.from(),
                range.to(),
                total.person(),
                total.kind(),
                total.counts(),
                total.firstContribution(),
                total.breakdown(),
                weeks,
                byRepository);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PublicActivityPersonDetailDTO publicPerson(
            long workspace,
            String login,
            ActivityPeopleRangeParams params,
            Set<String> repositoryKeys,
            boolean contributorsWithoutAccount) {
        var repositories = queries.publicRepositories(workspace, visibility.confirmedAfter());
        var selected = select(workspace, null, repositoryKeys, List.of(), repositories);
        var scope = ActivityScope.published(
                workspace, selected.repositoryIds(), withoutAccount(contributorsWithoutAccount));
        var contributor = publicContributor(scope, login);
        var range = params.resolve(clock, () -> queries.findEarliest(history(scope)));
        return PublicActivityPersonDetailDTO.from(
                detail(scope, range, contributor.person().id(), false, contributor, repositories));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public CursorPageDTO<PublicActivityWorkDTO> publicPersonWork(
            long workspace,
            String login,
            ActivityPeopleRangeParams params,
            Set<String> repositoryKeys,
            ActivityWorkFilterParams page,
            boolean contributorsWithoutAccount) {
        var repositories = queries.publicRepositories(workspace, visibility.confirmedAfter());
        var selected = select(workspace, null, repositoryKeys, List.of(), repositories);
        var scope = ActivityScope.published(
                workspace, selected.repositoryIds(), withoutAccount(contributorsWithoutAccount));
        var contributor = publicContributor(scope, login);
        var kinds = page.kinds() == null || page.kinds().isEmpty()
                ? PublicActivityPersonDetailDTO.SUPPORTED_KINDS
                : List.copyOf(page.kinds());
        if (!PublicActivityPersonDetailDTO.SUPPORTED_KINDS.containsAll(kinds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This kind is not published in public activity.");
        }
        var range = page.range(params, clock, () -> queries.findEarliest(history(scope)));
        var groups = publicWork.findWork(
                scope,
                range,
                contributor.person().id(),
                false,
                new PublicActivityWorkQueryRepository.Selection(
                        kinds.stream().map(kind -> kind.eventType().name()).toList(), page.pageSize() + 1),
                page.position(range));
        boolean hasNext = groups.size() > page.pageSize();
        var content = hasNext ? groups.subList(0, page.pageSize()) : groups;
        return new CursorPageDTO<>(
                content.stream()
                        .map(group -> new PublicActivityWorkDTO(
                                group.getId(),
                                new PublicActivityWorkSummaryDTO(
                                        WorkItemType.valueOf(group.getType()),
                                        group.getNumber(),
                                        group.getTitle(),
                                        State.valueOf(group.getState()),
                                        group.getDraft(),
                                        group.getHtmlUrl(),
                                        new PublicActivityRepositoryDTO(
                                                group.getRepositoryKey(), group.getRepositoryName())),
                                PublicActivityPersonDetailDTO.SUPPORTED_KINDS.stream()
                                        .filter(kind -> group.count(kind) > 0)
                                        .map(kind -> new ActivityActionDTO(kind, Math.toIntExact(group.count(kind))))
                                        .toList(),
                                group.getLastOccurredAt()))
                        .toList(),
                hasNext
                        ? new ActivityWorkCursor(
                                        range.to(),
                                        content.getLast().getLastOccurredAt(),
                                        content.getLast().getId())
                                .encode()
                        : null);
    }

    // Hiding names must not change the period or the coverage of public history.
    private static ActivityScope history(ActivityScope scope) {
        return scope.publicOnly() ? scope.withoutAccount(ActivityScope.WithoutAccount.SHOWN) : scope;
    }

    private ActivityPeopleQueryRepository.Contributor publicContributor(ActivityScope scope, String login) {
        var matches = scope.repositoryIds().isEmpty()
                ? List.<ActivityPeopleQueryRepository.ContributorRow>of()
                : queries.findPublicContributor(scope, login);
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This public activity page is not available.");
        }
        var person = matches.getFirst();
        return new ActivityPeopleQueryRepository.Contributor(person.person(), person.kind());
    }

    ActivityScope select(
            long workspace,
            @Nullable String team,
            Set<String> repositoryKeys,
            List<ActivityTeamDTO> teams,
            List<ActivityRepositoryDTO> repositories) {
        Long teamId = team == null
                ? null
                : teams.stream()
                        .filter(candidate -> candidate.key().equals(team))
                        .map(candidate -> candidate.id())
                        .findFirst()
                        .orElseThrow(() -> new EntityNotFoundException("Team", team));
        Set<Long> selected = repositories.stream()
                .filter(repo -> repositoryKeys.isEmpty() || repositoryKeys.contains(repo.key()))
                .map(repo -> repo.id())
                .collect(Collectors.toSet());
        if (!repositoryKeys.isEmpty() && selected.size() != repositoryKeys.size()) {
            throw new EntityNotFoundException("Repository", String.join(",", repositoryKeys));
        }

        Set<Long> teamIds = new HashSet<>();
        if (teamId != null) {
            var pending = new ArrayDeque<Long>(List.of(teamId));
            while (!pending.isEmpty()) {
                Long next = pending.pop();
                if (teamIds.add(next)) {
                    teams.stream()
                            .filter(candidate -> next.equals(candidate.parentId()))
                            .map(ActivityTeamDTO::id)
                            .forEach(pending::add);
                }
            }
        }
        return new ActivityScope(workspace, Set.of(), Set.copyOf(teamIds), selected);
    }
}
