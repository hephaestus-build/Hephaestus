package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanReuse;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScanListedReuse implements ReviewScanReuse {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository practices;
    private final ReviewItemRepository items;
    private final ReviewScanStore scans;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reuseListed(long workspaceId, UUID reviewId, Instant deadline) {
        var review = reviews.findByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (!acceptsReuse(review) || review.getChecks().path("reuseComplete").asBoolean()) return;
        if (review.getInventoryReceipt() == null
                || !review.getInventoryReceipt().path("complete").asBoolean()
                || practices.findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, reviewId).stream()
                        .anyMatch(p -> !Boolean.TRUE.equals(p.getListingComplete()))) return;
        while (clock.instant().isBefore(deadline)) {
            var pending = items.pendingReuse(workspaceId, reviewId, PageRequest.of(0, 1000));
            if (pending.isEmpty()) {
                transactions.executeWithoutResult(status -> {
                    var locked = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId)
                            .orElseThrow();
                    if (!acceptsReuse(locked)) return;
                    var checks = (ObjectNode) locked.getChecks().deepCopy();
                    checks.put("reuseComplete", true);
                    locked.setChecks(checks);
                });
                return;
            }
            for (var member : pending) {
                if (!clock.instant().isBefore(deadline)) return;
                boolean continued = Boolean.TRUE.equals(transactions.execute(status -> {
                    var locked = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId)
                            .orElseThrow();
                    if (!acceptsReuse(locked)) return false;
                    scans.reuse(
                            workspaceId, new ReviewAssignmentId(reviewId, member.getPracticeId(), member.getItemId()));
                    return true;
                }));
                if (!continued) return;
            }
        }
    }

    private static boolean acceptsReuse(Review review) {
        return review.getKind() == ReviewKind.SCAN && !review.getStatus().isTerminal() && !review.isCancelRequested();
    }
}
