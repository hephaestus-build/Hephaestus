package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewStartService implements ReviewStart {
    private final ReviewRepository reviews;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean start(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus().isTerminal()
                || review.getChecks().path("cancellationRequested").asBoolean()) return false;
        if (review.getStatus() == ReviewStatus.QUEUED) {
            review.setStatus(ReviewStatus.RUNNING);
            var checks = (ObjectNode) review.getChecks().deepCopy();
            checks.put("startedAt", clock.instant().toString());
            review.setChecks(checks);
        }
        return true;
    }
}
