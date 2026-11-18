package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemSourceRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnServerRole
public class FeedbackTargetResolver {
    private final FeedbackBasisRepository basis;
    private final ReviewRepository reviews;
    private final ItemSourceRegistry sources;

    public FeedbackTarget resolve(Feedback feedback) {
        return resolve(feedback, false);
    }

    public FeedbackTarget resolveExisting(Feedback feedback) {
        return resolve(feedback, true);
    }

    private FeedbackTarget resolve(Feedback feedback, boolean existing) {
        long itemId = Objects.requireNonNull(feedback.getItemId());
        var rows = basis.findItems(feedback.getWorkspaceId(), List.of(itemId));
        if (rows.size() != 1 || rows.getFirst().getRemovedAt() != null)
            throw new TargetUnavailable(FeedbackStopReason.ITEM_GONE);
        var row = rows.getFirst();
        var item = new ItemRef(
                feedback.getWorkspaceId(),
                itemId,
                new ItemKind(row.getKind()),
                row.getSourceId(),
                row.getParentId(),
                row.getPath());
        var review = reviews.findByIdAndWorkspaceId(feedback.getReviewId(), feedback.getWorkspaceId())
                .orElseThrow();
        var version = new ItemVersion(Objects.requireNonNull(review.getItemVersion()), review.getBaseVersion());
        try {
            var source = sources.sourceFor(item);
            // An edit, a retraction or a readback acts on the copy already on the work, whatever its version is now.
            var snapshot = source.pin(item, existing ? null : version);
            return new FeedbackTarget(item, snapshot.integration(), version);
        } catch (ItemCaptureException failure) {
            switch (failure.code()) {
                case ITEM_REMOVED -> throw new TargetUnavailable(FeedbackStopReason.ITEM_GONE, failure);
                case ITEM_NOT_VISIBLE -> throw new TargetUnavailable(FeedbackStopReason.ITEM_NOT_VISIBLE, failure);
                case CONSENT_WITHHELD -> throw new TargetUnavailable(FeedbackStopReason.CONSENT_WITHHELD, failure);
                case VERSION_UNAVAILABLE ->
                    throw new TargetUnavailable(FeedbackStopReason.REVIEWED_VERSION_CHANGED, failure);
                default -> throw failure;
            }
        }
    }

    public static final class TargetUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FeedbackStopReason reason;

        private TargetUnavailable(FeedbackStopReason reason) {
            super("The feedback item is unavailable.");
            this.reason = reason;
        }

        private TargetUnavailable(FeedbackStopReason reason, Throwable cause) {
            super("The feedback item is unavailable.", cause);
            this.reason = reason;
        }

        public FeedbackStopReason reason() {
            return reason;
        }
    }
}
