package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewQueueDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewQueueItemDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.time.Clock;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewQueueService {

    static final int HEAD_SIZE = 5;
    private final ReviewRuntimeQuery runtime;
    private final ProductReferenceQuery references;
    private final Clock clock;
    private final ReviewProgressService progressService;

    @Transactional(readOnly = true)
    public ReviewQueueDTO queue(long workspaceId) {
        var snapshot = runtime.queue(workspaceId, clock.instant(), HEAD_SIZE);
        var items = references.items(
                workspaceId, snapshot.head().stream().map(job -> job.itemId()).toList());
        var developers = references.developers(
                workspaceId,
                snapshot.head().stream()
                        .map(job -> job.developerId())
                        .filter(Objects::nonNull)
                        .toList());
        var progress = progressService.progress(
                workspaceId,
                snapshot.head().stream()
                        .collect(Collectors.toMap(
                                job -> job.reviewId(),
                                job -> job.status() == ReviewJobStatus.QUEUED
                                        ? ReviewStatus.QUEUED
                                        : ReviewStatus.RUNNING,
                                (first, second) -> first)));
        var head = snapshot.head().stream()
                .map(job -> new ReviewQueueItemDTO(
                        job.jobId(),
                        job.reviewId(),
                        job.status(),
                        job.heldForBudget(),
                        Objects.requireNonNull(items.get(job.itemId())),
                        job.developerId() == null ? null : developers.get(job.developerId()),
                        job.since(),
                        progress.get(job.reviewId())))
                .toList();
        return new ReviewQueueDTO(
                snapshot.asOf(),
                snapshot.running(),
                snapshot.activeWorkers(),
                snapshot.waiting(),
                snapshot.oldestWaitingSince(),
                snapshot.heldForBudget(),
                head,
                snapshot.finishedLastHour(),
                snapshot.finishedLastDay(),
                snapshot.finishedLastDay() / 24.0);
    }
}
