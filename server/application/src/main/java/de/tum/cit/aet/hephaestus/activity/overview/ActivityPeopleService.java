package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityBreakdownDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityHighlightsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySparklineWeekDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTeamDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWeekDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityPeopleService {
    private final ActivityPeopleQueryRepository queries;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPeopleDTO people(
            long workspace, ActivityPeopleRangeParams params, @Nullable String team, Set<String> repositoryKeys) {
        var teams = queries.teams(workspace);
        var repositories = queries.repositories(workspace);
        var scope = select(workspace, team, repositoryKeys, teams, repositories);
        var historyStart = queries.findEarliest(scope);
        var range = params.resolve(clock, Objects.requireNonNullElse(historyStart, clock.instant()));
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
                        row.getAutomation(),
                        row.getTreatedAsAutomation(),
                        row.counts(),
                        row.getFirstContribution(),
                        List.copyOf(weeks.getOrDefault(row.getActorId(), List.of()))))
                .toList();
        var people = all.stream().filter(row -> !row.automation()).toList();
        long mostHelped = people.stream()
                .mapToLong(row -> row.counts().peopleHelped())
                .max()
                .orElse(0);
        var coverage = queries.coverage(scope);
        boolean firstKnown = coverage.totalRepositories() > 0
                && coverage.completeRepositories() == coverage.totalRepositories()
                && coverage.since() != null
                && range.from().isAfter(coverage.since());
        var highlights = new ActivityHighlightsDTO(
                people.stream()
                        .filter(row -> firstKnown
                                && row.firstContributionAt() != null
                                && !row.firstContributionAt().isBefore(range.from())
                                && row.firstContributionAt().isBefore(range.to()))
                        .map(row -> row.person().id())
                        .toList(),
                people.stream()
                        .filter(row -> mostHelped > 0 && row.counts().peopleHelped() == mostHelped)
                        .map(row -> row.person().id())
                        .toList());
        return new ActivityPeopleDTO(
                range.from(),
                range.to(),
                all.stream().filter(row -> !row.automation()).toList(),
                all.stream().filter(ActivityPersonDTO::automation).toList(),
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
        var range = params.resolve(clock, queries.earliest(scope, clock.instant()));
        var rows = queries.people(scope, range, userId, own);
        var total = rows.stream()
                .filter(ActivityPeopleQueryRepository.PersonCount::total)
                .findFirst()
                .orElseGet(() -> new ActivityPeopleQueryRepository.PersonCount(
                        contributor.person(),
                        contributor.automation(),
                        contributor.treatedAsAutomation(),
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
                total.automation(),
                total.treatedAsAutomation(),
                total.counts(),
                total.firstContribution(),
                total.breakdown(),
                weeks,
                byRepository);
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
