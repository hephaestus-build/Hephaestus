package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityHighlightsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWeekDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityPeopleService {
    private final ActivityPeopleQueryRepository queries;
    private final ActivityScopeResolver scopes;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ActivityPeopleDTO people(
            long workspace,
            ActivityPeopleRangeParams params,
            @Nullable String team,
            Set<String> repositoryKeys,
            boolean membersOnly) {
        var teams = queries.teams(workspace);
        Long teamId = team == null
                ? null
                : teams.stream()
                        .filter(candidate -> candidate.key().equals(team))
                        .map(candidate -> candidate.id())
                        .findFirst()
                        .orElseThrow(() -> new EntityNotFoundException("Team", team));
        var repositories = queries.repositories(workspace);
        Set<Long> selected = repositories.stream()
                .filter(repo -> repositoryKeys.contains(repo.key()))
                .map(repo -> repo.id())
                .collect(Collectors.toSet());
        if (selected.size() != repositoryKeys.size()) {
            throw new EntityNotFoundException("Repository", String.join(",", repositoryKeys));
        }
        var range = params.resolve(clock, queries.earliest(workspace, clock.instant()));
        var rows = queries.people(workspace, range, scopes.teamIds(workspace, teamId), selected, membersOnly, 0);
        Map<Long, ActivityPeopleQueryRepository.PersonCount> totals = new LinkedHashMap<>();
        Map<Long, List<ActivityWeekDTO>> weeks = new LinkedHashMap<>();
        for (var row : rows) {
            long person = row.person().id();
            if (row.total()) {
                totals.put(person, row);
            } else if (row.week() != null) {
                weeks.computeIfAbsent(person, key -> new ArrayList<>())
                        .add(new ActivityWeekDTO(row.week(), row.counts()));
            }
        }
        List<ActivityPersonDTO> all = totals.values().stream()
                .map(row -> new ActivityPersonDTO(
                        row.person(),
                        row.automation(),
                        row.counts(),
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
                teams);
    }
}
