package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Renews a review's lease independently of the worker that launched its Pod. */
@Service
@ConditionalOnWorkerRole
@RequiredArgsConstructor
public class ReviewAttemptLease {
    private final ReviewJobRepository jobs;

    @Transactional
    public Optional<LeaseReceiptDTO> renew(AttemptJwt attempt) {
        if (attempt.kind() != AttemptJwt.Kind.REVIEW) return Optional.empty();
        return jobs.renewReviewLease(
                        attempt.id(),
                        attempt.workspaceId(),
                        attempt.attempt(),
                        attempt.leaseId(),
                        AgentProperties.WORKER_LEASE_TTL.toSeconds(),
                        attempt.expiresAt())
                .filter(remaining -> remaining > 0)
                .map(remaining -> new LeaseReceiptDTO(
                        remaining, Math.min(remaining, AgentProperties.WORKER_LEASE_TTL.toMillis() / 3)));
    }

    /** Database-clock duration; the caller subtracts the whole request time before it uses this receipt. */
    public record LeaseReceiptDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long remainingMillis,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long renewAfterMillis) {}
}
