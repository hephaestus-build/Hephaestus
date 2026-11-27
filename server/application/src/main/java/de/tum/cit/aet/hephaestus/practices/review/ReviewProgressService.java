package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewProgressDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewStage;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** How far each item review got, from its status and the practices that its attempts ended. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewProgressService {
    private final ReviewItemRepository items;

    /**
     * @param shown each review's status
     * @return progress keyed by review id; a scan has none
     */
    public Map<UUID, ReviewProgressDTO> progress(long workspaceId, Map<UUID, ReviewStatus> shown) {
        if (shown.isEmpty()) return Map.of();
        return items.progress(workspaceId, shown.keySet()).stream()
                .collect(Collectors.toUnmodifiableMap(
                        ReviewItemRepository.ReviewProgressCounts::getReviewId,
                        c -> progress(c, Objects.requireNonNull(shown.get(c.getReviewId())))));
    }

    private static ReviewProgressDTO progress(ReviewItemRepository.ReviewProgressCounts counts, ReviewStatus status) {
        int ended = Math.toIntExact(counts.getEnded());
        int total = Math.toIntExact(counts.getPractices());
        // Waiting with practices ended and some left: an attempt moved the review on, and the next one has not
        // started. A first start ended nothing, and a review with every practice ended is admitting its results.
        boolean continuing = status == ReviewStatus.QUEUED && ended > 0 && ended < total;
        var stage = ReviewStage.of(status, continuing, counts.getUnsettled(), counts.getChecked());
        return new ReviewProgressDTO(ended, total, continuing, stage);
    }
}
