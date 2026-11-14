package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRequestRejected;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewBatchSubmitter {
    public sealed interface SubmissionOutcome {
        record Submitted() implements SubmissionOutcome {}

        record Skipped() implements SubmissionOutcome {}

        record Deferred() implements SubmissionOutcome {}

        record Paused(ReviewBatchPauseReason reason) implements SubmissionOutcome {}
    }

    private final ProductItemQuery items;
    private final ReviewService reviews;
    private final ReviewRepository ledger;

    /** Not transactional: a scan's request pins the repository, which fetches its mirror, outside any transaction. */
    public SubmissionOutcome offer(ReviewBatch batch, long itemId, @Nullable BigDecimal budgetUsd) {
        var item = items.find(batch.getWorkspaceId(), itemId);
        if (item.isEmpty()) return new SubmissionOutcome.Skipped();
        ReviewKind kind =
                item.orElseThrow().kind().value().equals("scm.repository") ? ReviewKind.SCAN : ReviewKind.ITEM;
        if (ledger.existsByWorkspaceIdAndBatchIdAndItemIdAndStatusNot(
                batch.getWorkspaceId(), batch.getId(), itemId, ReviewStatus.CANCELLED))
            return new SubmissionOutcome.Submitted();
        try {
            reviews.request(ReviewRequest.builder()
                    .workspaceId(batch.getWorkspaceId())
                    .itemId(itemId)
                    .kind(kind)
                    .origin(ReviewBatchOrigins.origin(batch))
                    .batchId(batch.getId())
                    .budgetUsd(budgetUsd)
                    .practiceIds(List.of())
                    .reuse(true)
                    .build());
        } catch (ReviewRequestRejected refusal) {
            return switch (refusal.reason()) {
                case BUDGET_UNAVAILABLE -> new SubmissionOutcome.Paused(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
                case WORKSPACE_INACTIVE -> new SubmissionOutcome.Paused(ReviewBatchPauseReason.WORKSPACE_UNAVAILABLE);
                case MODEL_UNAVAILABLE -> new SubmissionOutcome.Paused(ReviewBatchPauseReason.REVIEW_MODEL_UNBOUND);
                // A newer version arrived while the request read the work: the next tick reviews that one.
                case SCAN_ACTIVE, VERSION_CHANGED -> new SubmissionOutcome.Deferred();
                case SOURCE_UNAVAILABLE, ITEM_INELIGIBLE, REQUEST_CONFLICT, REF_UNSUPPORTED ->
                    new SubmissionOutcome.Skipped();
            };
        }
        return new SubmissionOutcome.Submitted();
    }
}
