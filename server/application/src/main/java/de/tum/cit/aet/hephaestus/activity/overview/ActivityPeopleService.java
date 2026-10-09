package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityHighlightsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySummaryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTeamDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWeekDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.time.Clock;
import java.util.ArrayList;
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
    private final ActivityScopeResolver scopes;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPeopleDTO people(
            long workspace,
            ActivityPeopleRangeParams params,
            @Nullable String team,
            Set<String> repositoryKeys,
            boolean membersOnly) {
        var teams = queries.teams(workspace);
        var repositories = queries.repositories(workspace);
        var scope = select(workspace, team, repositoryKeys, teams, repositories);
        var historyStart = queries.findEarliest(workspace);
        var range = params.resolve(clock, Objects.requireNonNullElse(historyStart, clock.instant()));
        var rows = queries.people(workspace, range, scope.teamIds(), scope.repositoryIds(), membersOnly, 0);
        Map<Long, ActivityPeopleQueryRepository.PersonCount> totals = new LinkedHashMap<>();
        Map<Long, List<ActivityWeekDTO>> weeks = new LinkedHashMap<>();
        for (var row : rows) {
            long person = row.person().id();
            if (row.total()) {
                totals.put(person, row);
            } else if (row.week() != null) {
                weeks.computeIfAbsent(person, key -> new ArrayList<>())
                        .add(new ActivityWeekDTO(row.week(), row.counts(), row.breakdown()));
            }
        }
        List<ActivityPersonDTO> all = totals.values().stream()
                .map(row -> new ActivityPersonDTO(
                        row.person(),
                        row.automation(),
                        row.counts(),
                        row.breakdown(),
                        row.firstContribution(),
                        List.copyOf(weeks.getOrDefault(row.person().id(), List.of()))))
                .toList();
        var people = all.stream().filter(row -> !row.automation()).toList();
        long mostHelped = people.stream()
                .mapToLong(row -> row.counts().peopleHelped())
                .max()
                .orElse(0);
        var highlights = new ActivityHighlightsDTO(
                people.stream()
                        .filter(row -> row.firstContributionAt() != null
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
                queries.coverage(workspace),
                highlights,
                repositories,
                teams,
                ActivityPeopleRangeParams.MAX_DAYS,
                historyStart);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPersonDetailDTO person(
            long workspace,
            long userId,
            ActivityPeopleRangeParams params,
            @Nullable String team,
            Set<String> repositoryKeys) {
        var contributor = queries.contributor(workspace, userId);
        var repositories = queries.repositories(workspace);
        var scope = select(workspace, team, repositoryKeys, queries.teams(workspace), repositories);
        var range = params.resolve(clock, queries.earliest(workspace, clock.instant()));
        var rows = queries.people(workspace, range, scope.teamIds(), scope.repositoryIds(), false, userId);
        var total = rows.stream()
                .filter(ActivityPeopleQueryRepository.PersonCount::total)
                .findFirst()
                .orElseGet(() -> new ActivityPeopleQueryRepository.PersonCount(
                        contributor.person(),
                        contributor.automation(),
                        true,
                        null,
                        null,
                        null,
                        new ActivityCountsDTO(0, 0, 0, 0, 0, 0, 0, 0),
                        new ActivitySummaryDTO(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
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
                new ActivityPersonDTO(
                        total.person(),
                        total.automation(),
                        total.counts(),
                        total.breakdown(),
                        total.firstContribution(),
                        weeks),
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

        return new ActivityScope(workspace, Set.of(), scopes.teamIds(workspace, teamId), selected);
    }
}
