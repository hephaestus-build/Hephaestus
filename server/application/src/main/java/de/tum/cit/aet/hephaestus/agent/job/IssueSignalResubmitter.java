package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Issue-side mirror of {@link PullRequestSignalResubmitter}, with the same replay-the-decision rule. */
@Component
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
public class IssueSignalResubmitter implements PendingSignalResubmitter {

    private static final Logger log = LoggerFactory.getLogger(IssueSignalResubmitter.class);

    private final AgentJobService agentJobService;
    private final IssueRepository issueRepository;
    private final PracticeReviewDetectionGate practiceReviewDetectionGate;
    private final SignalRecorder signalRecorder;

    public IssueSignalResubmitter(
            AgentJobService agentJobService,
            IssueRepository issueRepository,
            PracticeReviewDetectionGate practiceReviewDetectionGate,
            SignalRecorder signalRecorder) {
        this.agentJobService = agentJobService;
        this.issueRepository = issueRepository;
        this.practiceReviewDetectionGate = practiceReviewDetectionGate;
        this.signalRecorder = signalRecorder;
    }

    @Override
    public ArtifactKind artifactKind() {
        return ScmSignals.ISSUE;
    }

    /**
     * Joins the caller's transaction, unlike {@link PullRequestSignalResubmitter#resubmit}: called from
     * {@link IssueUpdateCoalescer#drain}, this settlement must commit or roll back with the rest of the
     * burst it is part of, not on its own.
     */
    @Override
    @Transactional
    public void resubmit(ArtifactSignal signal) {
        SignalKey key = signal.key();
        Issue issue = issueRepository
                .findByIdWithRepositoryAndAssignees(key.artifactId())
                .orElse(null);
        if (issue != null && issue.getDeletedAt() != null) {
            log.debug("Pending signal's issue is not visible upstream: issueId={}", issue.getId());
            signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_NOT_VISIBLE);
            return;
        }
        if (issue == null || issue.getRepository() == null) {
            log.debug("Pending signal has no reviewable issue left: issueId={}", key.artifactId());
            signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_GONE);
            return;
        }

        if (key.signalName().equals(ScmSignals.ISSUE_UPDATED)
                && (issue.getState() == Issue.State.CLOSED
                        || !key.revision()
                                .equals(ScmSignals.issueUpdatedRevision(ScmEventPayload.IssueData.from(issue))))) {
            signalRecorder.markRefused(key, SignalStateReason.COALESCED);
            return;
        }

        switch (practiceReviewDetectionGate.evaluateIssue(
                issue, key.workspaceId(), key.signalName(), TriggerMode.AUTO)) {
            case GateDecision.Skip skip -> {
                log.debug(
                        "Pending signal now skipped by practice gate: issueId={}, reason={}",
                        issue.getId(),
                        skip.reason());
                signalRecorder.markRefused(key, skip.resolvedSignalReason());
            }
            case GateDecision.Detect detect ->
                agentJobService.submit(
                        detect.workspace().getId(),
                        AgentJobType.ISSUE_REVIEW,
                        new IssueReviewSubmissionRequest(
                                issue.getId(),
                                issue.getNumber(),
                                issue.getRepository().getId(),
                                issue.getRepository().getNameWithOwner(),
                                issue.getTitle(),
                                issue.getBody() != null ? issue.getBody() : "",
                                issue.getState() != null ? issue.getState().name() : "OPEN",
                                issue.getHtmlUrl(),
                                issue.getUpdatedAt(),
                                key.signalName(),
                                // See PullRequestSignalResubmitter: the ledger row's discovery mode is the only thing
                                // that still remembers which population this review was meant to measure.
                                SignalOrigins.observationOriginOf(signal.getDiscoveredVia()),
                                signal.getActorUserId(),
                                issue.getReviewSnapshotId()),
                        key,
                        detect);
        }
    }
}
