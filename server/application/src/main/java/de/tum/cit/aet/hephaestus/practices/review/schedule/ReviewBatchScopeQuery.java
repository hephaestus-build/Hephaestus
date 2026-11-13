package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewBatchScopeQuery {
    private final ProductItemRepository items;
    private final ReviewBatchTargetRepository targets;
    private final PracticeReviewCoverageService coverage;
    private final WorkspaceQueries workspaces;
    private final MonitoredRepositoryQuery monitored;

    public List<Long> select(long ws, String kind, @Nullable Instant from, @Nullable Instant to, int limit) {
        if (kind.equals("scm.repository")) {
            var workspace = workspaces.findById(ws).orElseThrow();
            return monitored.repositories(ws).keySet().stream()
                    .sorted()
                    .filter(id -> coverage.admitsRepositoryItem(workspace, id))
                    .limit(limit)
                    .toList();
        }
        var page = PageRequest.of(0, limit);
        return from == null
                ? items.scopeItems(ws, kind, page)
                : items.scopeItemsInWindow(ws, kind, from, Objects.requireNonNull(to), page);
    }

    public List<Long> next(long ws, UUID batch, @Nullable Long cursor, int limit) {
        return targets
                .findByWorkspaceIdAndBatchIdAndItemIdGreaterThanOrderByItemIdAsc(
                        ws, batch, cursor == null ? 0 : cursor, PageRequest.of(0, limit))
                .stream()
                .map(ReviewBatchTarget::getItemId)
                .toList();
    }
}
