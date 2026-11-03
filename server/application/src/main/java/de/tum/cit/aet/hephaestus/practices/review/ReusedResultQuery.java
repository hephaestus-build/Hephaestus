package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The earlier results that a review kept instead of measuring again. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReusedResultQuery {
    private final ReviewItemRepository memberships;
    private final ResultRepository results;

    @Transactional(readOnly = true)
    public List<Result> of(long workspaceId, UUID reviewId) {
        var ids = memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId).stream()
                .filter(member -> member.getState() == ReviewItemState.REUSED)
                .map(ReviewItem::getResultId)
                .filter(Objects::nonNull)
                .toList();
        return ids.isEmpty()
                ? List.of()
                : results.findAllById(ids).stream()
                        .filter(result -> result.getWorkspaceId() == workspaceId)
                        .toList();
    }
}
