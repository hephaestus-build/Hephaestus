package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewProgressDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** How far each active review got, from the practices that its attempts ended. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewProgressService {
    private final ReviewItemRepository items;

    /**
     * @param shown each review's status; a review that is neither queued nor running gets no progress
     * @return progress keyed by review id; a review whose practices are not listed yet is absent
     */
    public Map<UUID, ReviewProgressDTO> progress(long workspaceId, Map<UUID, ReviewStatus> shown) {
        var active = shown.entrySet().stream()
                .filter(e -> e.getValue() == ReviewStatus.QUEUED || e.getValue() == ReviewStatus.RUNNING)
                .map(Map.Entry::getKey)
                .toList();
        if (active.isEmpty()) return Map.of();
        return items.progress(workspaceId, active).stream()
                .collect(Collectors.toUnmodifiableMap(
                        ReviewItemRepository.ReviewProgressCounts::getReviewId,
                        c -> progress(c, shown.get(c.getReviewId()) == ReviewStatus.QUEUED)));
    }

    private static ReviewProgressDTO progress(ReviewItemRepository.ReviewProgressCounts counts, boolean waiting) {
        int ended = Math.toIntExact(counts.getEnded());
        int total = Math.toIntExact(counts.getPractices());
        // Waiting with practices ended and some left: an attempt moved the review on, and the next one has not
        // started. A first start ended nothing, and a review with every practice ended is admitting its results.
        return new ReviewProgressDTO(ended, total, waiting && ended > 0 && ended < total);
    }
}
