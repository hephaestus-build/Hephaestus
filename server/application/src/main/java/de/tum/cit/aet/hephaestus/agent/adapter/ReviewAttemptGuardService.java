package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReviewAttemptGuardService implements ReviewAttemptGuard {
    private final ReviewJobRepository jobs;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOwner(long workspaceId, UUID jobId, int attempt) {
        var job = jobs.findByIdAndWorkspaceIdForUpdate(jobId, workspaceId)
                .orElseThrow(ResultAdmissionService.StaleAttemptException::new);
        if (job.getStatus() != ReviewJobStatus.RUNNING || job.getAttempt() != attempt || job.getLeaseId() == null)
            throw new ResultAdmissionService.StaleAttemptException();
    }
}
