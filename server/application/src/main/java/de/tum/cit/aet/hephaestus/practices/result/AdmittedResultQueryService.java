package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class AdmittedResultQueryService implements AdmittedResultQuery {
    private final ReviewRepository reviews;
    private final ReviewItemRepository items;
    private final ResultRepository results;
    private final ResultProofMapper proof;

    @Override
    @Transactional(readOnly = true)
    public List<AdmittedResultSnapshot> forAttempt(long workspaceId, UUID reviewId, UUID jobId, int attempt) {
        if (attempt < 0 || reviews.findByIdAndWorkspaceId(reviewId, workspaceId).isEmpty()) return List.of();
        return items.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId).stream()
                .filter(i -> i.getState() == ReviewItemState.JUDGED
                        && jobId.equals(i.getJobId())
                        && i.getDetail() != null
                        && i.getDetail().has("admissionAttempt")
                        && i.getDetail().path("admissionAttempt").isInt()
                        && i.getDetail().path("admissionAttempt").intValue() == attempt)
                .map(i -> results.findByIdAndWorkspaceId(Objects.requireNonNull(i.getResultId()), workspaceId)
                        .filter(r -> r.getReviewId().equals(reviewId)
                                && r.getPracticeId() == i.getPracticeId()
                                && r.getItemId() == i.getItemId()
                                && !r.isMigratedHistory()
                                && r.getEvidence() != null
                                && r.getCoverage() != null
                                && r.getPracticeVersionId() != null
                                && r.getItemVersion() != null
                                && r.getSummary() != null
                                && r.getExplanation() != null))
                .flatMap(Optional::stream)
                .map(proof::snapshot)
                .toList();
    }
}
