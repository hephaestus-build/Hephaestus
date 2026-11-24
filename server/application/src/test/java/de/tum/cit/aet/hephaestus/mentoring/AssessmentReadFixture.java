package de.tum.cit.aet.hephaestus.mentoring;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAssignmentQueryService;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record AssessmentReadFixture(
        ResultHistoryQuery history,
        AdmittedResultQuery admitted,
        ResultEvidenceQuery evidence,
        ReviewJobRepository jobs) {
    public void read(EntityManager entities, long workspace, long account, UUID review, UUID job) {
        history.forDeveloper(workspace, account, Instant.EPOCH, 1);
        admitted.forAttempt(workspace, review, job, 1);
        evidence.problemCitations(workspace, List.of(review));
        jobs.findById(job);
        entities.find(ReviewJob.class, job);
    }

    public void readReviewStatus(ReviewAssignmentQuery assignments, long workspace, long account, UUID review) {
        assignments.recordedForDeveloper(
                workspace, account, List.of(new ReviewAssignmentQuery.ReviewedItem(review, 1)));
    }

    public void readConcreteReviewStatus(
            ReviewAssignmentQueryService assignments, long workspace, long account, UUID review) {
        assignments.recordedForDeveloper(
                workspace, account, List.of(new ReviewAssignmentQuery.ReviewedItem(review, 1)));
    }
}
