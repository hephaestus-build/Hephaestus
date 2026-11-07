package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
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
        if (jobs.renewLease(
                        attempt.id(),
                        attempt.workspaceId(),
                        attempt.attempt(),
                        attempt.leaseId(),
                        AgentProperties.WORKER_LEASE_TTL.toSeconds())
                == 0) return Optional.empty();
        return jobs.remainingLeaseMillis(
                        attempt.id(), attempt.workspaceId(), attempt.attempt(), attempt.leaseId(), attempt.expiresAt())
                .filter(remaining -> remaining >= 3)
                .map(remaining -> new LeaseReceiptDTO(remaining, remaining / 3));
    }

    /** Database-clock duration; the caller subtracts the whole request time before it uses this receipt. */
    public record LeaseReceiptDTO(long remainingMillis, long renewAfterMillis) {}
}
