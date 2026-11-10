package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemMergeTimeQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Retained item identities scope actual provider merge facts without preparing a new capture. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmItemMergeTimeQuery implements ItemMergeTimeQuery {
    private final ScmItemAccess items;
    private final ScmItemQueryRepository queries;
    private final RepositoryRepository repositories;
    private final ScmRepositoryAccess access;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Instant> mergedAt(long workspaceId, List<ItemRef> refs) {
        if (workspaceId <= 0 || refs.stream().anyMatch(ref -> ref.workspaceId() != workspaceId))
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Item does not belong to the requested workspace");
        var supported = refs.stream()
                .filter(ref -> ref.kind().equals(ScmItemEvents.PULL_REQUEST)
                        || ref.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW))
                .distinct()
                .toList();
        if (supported.isEmpty()) return Map.of();
        items.requireAll(workspaceId, supported);
        var rows = queries.mergeFacts(
                workspaceId,
                supported.stream().map(ItemRef::itemId).toList(),
                ScmItemEvents.REPOSITORY.value(),
                ScmItemEvents.PULL_REQUEST_REVIEW.value(),
                ScmItemEvents.PULL_REQUEST.value());
        if (rows.size() != supported.size()) throw unavailable();
        Map<Long, Instant> result = new HashMap<>();
        var repositoryIds = rows.stream()
                .map(ScmItemQueryRepository.MergeRow::getRepositoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var repositoryRows = repositories.findAllById(repositoryIds).stream()
                .collect(Collectors.toMap(Repository::getId, Function.identity()));
        if (repositoryRows.size() != repositoryIds.size()) throw unavailable();
        repositoryRows.values().forEach(repository -> access.authorize(workspaceId, repository));
        for (var row : rows) {
            if (row.getPullId() == null
                    || row.getRepositoryItemId() == null
                    || !"PULL_REQUEST".equals(row.getIssueType())) throw unavailable();
            Long repositoryId = row.getRepositoryId();
            if (repositoryId == null) throw unavailable();
            Instant at = row.getMergedAt();
            if (at != null) result.put(row.getId(), at);
        }
        return Map.copyOf(result);
    }

    private static ItemCaptureException unavailable() {
        return new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "SCM merge timing facts are unavailable");
    }
}
