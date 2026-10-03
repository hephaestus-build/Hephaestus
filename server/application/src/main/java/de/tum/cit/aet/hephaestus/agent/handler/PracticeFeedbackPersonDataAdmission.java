package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Serializes provider egress and erasure without retaining the erased subject in another store. */
@Component
@RequiredArgsConstructor
class PracticeFeedbackPersonDataAdmission {
    private final PersonDataCopyFence personCopies;
    private final PersonProcessingSuppression personSuppression;

    PracticeFeedbackDispatchService.Result deliver(
            AgentJob job, Supplier<PracticeFeedbackDispatchService.Result> delivery) {
        try (var capture = personCopies.capture()) {
            // Use the leased session, not a possibly stale source/JPA transaction. Erasure
            // revokes the job hash; a queued caller holding the old entity cannot recreate copies.
            boolean current = Boolean.TRUE.equals(capture.jdbc()
                    .queryForObject(
                            """
                    SELECT EXISTS(SELECT 1 FROM agent_job
                      WHERE id=? AND workspace_id=? AND job_token_hash=?)
                    """, Boolean.class, job.getId(), job.getWorkspace().getId(), job.getJobTokenHash()));
            if (!current || personSuppression.isReviewJobSuppressed(job.getId()))
                return PracticeFeedbackDispatchService.Result.suppressed(FeedbackSuppressionReason.ARTIFACT_GONE);
            var metadata = job.getMetadata();
            if (metadata != null) {
                for (String key : List.of("author_id", "actor_user_id", "about_user_id")) {
                    var user = metadata.path(key);
                    if (user.isIntegralNumber() && personSuppression.isUserSuppressed(user.asLong()))
                        return PracticeFeedbackDispatchService.Result.suppressed(
                                FeedbackSuppressionReason.ARTIFACT_GONE);
                }
            }
            // Admission spans intent persistence, provider requests and outcome persistence.
            // A preview may precede this write; its delivery fingerprint catches the change.
            return delivery.get();
        }
    }
}
