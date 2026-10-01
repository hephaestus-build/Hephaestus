package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultTargetResolver.Target;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class LinkedIssueRepairAdmissionService {
    private final ReviewedWorkChanges reviewedWorkChanges;
    private final ObservationRepository observations;
    private final FeedbackObservationRepository feedbackObservations;
    private final FeedbackRepository feedback;

    public void requireCurrentCapture(AgentJob job, long artifactId, String revision) {
        if (revision.isBlank()
                || !reviewedWorkChanges.linkedCaptureCurrent(
                        job.getWorkspace().getId(), job.getId(), artifactId, revision)) {
            throw new ObservationsRefusedException(
                    "linked_issue_capture_stale", "Linked issue changed since capture: jobId=" + job.getId());
        }
    }

    public Map<Long, List<Observation>> currentNegatives(
            long workspaceId, ArtifactKind kind, long artifactId, long developerId) {
        return LatestRun.perClaim(observations.findStandingForWork(workspaceId, kind, artifactId, developerId)).stream()
                .filter(observation -> observation.getOutcome().isNotMet())
                .collect(Collectors.groupingBy(
                        observation -> observation.getPractice().getId()));
    }

    public void retire(
            long workspaceId, Target target, long practiceId, List<Observation> replaced, Instant observedAt) {
        for (UUID feedbackId : feedbackObservations.findPreparedConversationFeedbackIdsForNegativeClaim(
                workspaceId, target.aboutUserId(), practiceId, target.type(), target.id())) {
            feedback.markSuperseded(workspaceId, feedbackId);
        }
        for (Observation previous : replaced) {
            observations.supersedeById(workspaceId, previous.getId(), observedAt);
        }
    }
}
