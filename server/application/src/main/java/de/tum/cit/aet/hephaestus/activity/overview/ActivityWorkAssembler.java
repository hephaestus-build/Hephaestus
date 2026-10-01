package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository.WorkGroup;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityActionDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns a page of work groups into work list entries, loading the page's pull requests, issues and people once
 * each. A group whose work is gone stays in the list without it, so the list keeps matching the counts.
 */
@Component
@RequiredArgsConstructor
class ActivityWorkAssembler {

    private static final String WORK = "work:";

    private final WorkItemQueryRepository workItems;
    private final UserRepository users;

    List<ActivityWorkDTO> assemble(List<WorkGroup> groups) {
        Set<Long> workIds = groups.stream()
                .map(WorkGroup::getId)
                .filter(id -> id.startsWith(WORK))
                .map(ActivityWorkAssembler::workId)
                .collect(Collectors.toSet());
        Map<Long, Issue> workById = workIds.isEmpty()
                ? Map.of()
                : workItems.findAllByIdIn(workIds).stream()
                        .collect(Collectors.toMap(Issue::getId, Function.identity()));
        Map<Long, User> userById = users
                .findAllById(
                        groups.stream().flatMap(ActivityWorkAssembler::actorIds).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return groups.stream()
                .map(group -> new ActivityWorkDTO(
                        group.getId(),
                        group.getId().startsWith(WORK)
                                ? WorkItemDTO.fromAvailable(workById.get(workId(group.getId())))
                                : null,
                        Arrays.stream(ActivityKind.values())
                                .filter(kind -> group.count(kind) > 0)
                                .map(kind -> new ActivityActionDTO(kind, (int) group.count(kind)))
                                .toList(),
                        group.getLastOccurredAt(),
                        actorIds(group)
                                .map(userById::get)
                                .filter(Objects::nonNull)
                                .sorted(ActivityScopeResolver.BY_NAME)
                                .map(user -> Objects.requireNonNull(UserInfoDTO.fromUser(user)))
                                .toList()))
                .toList();
    }

    private static Stream<Long> actorIds(WorkGroup group) {
        return Arrays.stream(group.getActorIds().split(",")).map(Long::valueOf);
    }

    private static Long workId(String groupId) {
        return Long.valueOf(groupId.substring(WORK.length()));
    }
}
