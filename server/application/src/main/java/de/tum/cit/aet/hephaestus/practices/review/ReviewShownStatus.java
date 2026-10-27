package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.util.Map;

/**
 * The status that a review reports, as the glossary defines it: a started review whose invocations all
 * wait for a worker is Queued, not Running. The stored status turns Running when the review is handed to
 * the queue, which the invocation fence relies on; nothing runs until a worker claims a job.
 *
 * <p>{@link #of} and {@link #SQL} state the same rule, one for a review in hand and one for a filter or
 * count over many; {@code ReviewSummaryControllerIntegrationTest} holds them equal.
 */
public final class ReviewShownStatus {

    /** The rule over a review aliased {@code q}, as text that a native query can compare and group by. */
    public static final String SQL = "(CASE WHEN q.status='RUNNING'"
            + " AND NOT EXISTS(SELECT 1 FROM review_job j WHERE j.workspace_id=q.workspace_id"
            + " AND j.review_id=q.id AND j.status='RUNNING')"
            + " AND EXISTS(SELECT 1 FROM review_job j WHERE j.workspace_id=q.workspace_id"
            + " AND j.review_id=q.id AND j.status='QUEUED')"
            + " THEN 'QUEUED' ELSE q.status END)";

    private ReviewShownStatus() {}

    /**
     * @param jobs the review's jobs counted by status; empty when it has none
     */
    public static ReviewStatus of(ReviewStatus stored, Map<ReviewJobStatus, Long> jobs) {
        return stored == ReviewStatus.RUNNING
                        && jobs.getOrDefault(ReviewJobStatus.RUNNING, 0L) == 0
                        && jobs.getOrDefault(ReviewJobStatus.QUEUED, 0L) > 0
                ? ReviewStatus.QUEUED
                : stored;
    }
}
