package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationCandidateSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.agent.handler.ConversationReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@ConditionalOnServerRole
@Component
public class ConversationReviewSubmitter implements PendingSignalResubmitter {

    private static final Logger log = LoggerFactory.getLogger(ConversationReviewSubmitter.class);

    private final ConversationCandidateSource candidateSource;
    private final AgentJobService agentJobService;
    private final SignalRecorder signalRecorder;
    private final TransactionTemplate transactionTemplate;
    private final WorkspaceRepository workspaceRepository;
    private final ReviewGate reviewGate;

    public ConversationReviewSubmitter(
            ConversationCandidateSource candidateSource,
            AgentJobService agentJobService,
            SignalRecorder signalRecorder,
            TransactionTemplate transactionTemplate,
            WorkspaceRepository workspaceRepository,
            ReviewGate reviewGate) {
        this.candidateSource = candidateSource;
        this.agentJobService = agentJobService;
        this.signalRecorder = signalRecorder;
        this.transactionTemplate = transactionTemplate;
        this.workspaceRepository = workspaceRepository;
        this.reviewGate = reviewGate;
    }

    @Override
    public ArtifactKind artifactKind() {
        return ChatSignals.CONVERSATION_THREAD;
    }

    @Override
    public void resubmit(ArtifactSignal signal) {
        long workspaceId = signal.getWorkspace().getId();
        ConversationThreadCandidate candidate = candidateSource
                .candidateById(workspaceId, signal.getArtifactId())
                .orElse(null);
        if (candidate == null) {
            log.debug(
                    "Conversation signal has no consented thread left to review: workspaceId={}, threadId={}",
                    workspaceId,
                    signal.getArtifactId());
            settleRefused(signal.key(), SignalStateReason.ARTIFACT_GONE);
            return;
        }
        submitAndSettle(candidate, signal.key());
    }

    public long submitAndSettle(ConversationThreadCandidate candidate, SignalKey key) {
        Workspace workspace =
                workspaceRepository.findById(candidate.workspaceId()).orElse(null);
        if (workspace == null) {
            settleRefused(key, SignalStateReason.ARTIFACT_GONE);
            return 0;
        }
        long started = 0;
        UUID firstJobId = null;
        SignalStateReason firstRefusal = null;
        for (long participant : candidate.participantMemberIds()) {
            if (participant <= 0) {
                continue;
            }
            try {
                GateDecision decision = reviewGate.evaluateSignal(
                        workspace, key.signalName(), TriggerMode.AUTO, new ReviewSubject(participant, true));
                if (decision instanceof GateDecision.Skip skip) {
                    if (firstRefusal == null) firstRefusal = skip.resolvedSignalReason();
                    continue;
                }
                GateDecision.Detect detect = (GateDecision.Detect) decision;
                SubmissionOutcome outcome = agentJobService.submitWithOutcome(
                        candidate.workspaceId(),
                        AgentJobType.CONVERSATION_REVIEW,
                        requestFor(candidate, participant),
                        null,
                        detect);
                if (outcome.job() != null) {
                    started++;
                    if (firstJobId == null) {
                        firstJobId = outcome.job().getId();
                    }
                } else if (firstRefusal == null) {
                    firstRefusal = outcome.requireRefusal();
                }
            } catch (RuntimeException e) {
                log.warn(
                        "conversation.detect: enqueue failed for threadId={}, participant={}: {}",
                        candidate.threadId(),
                        participant,
                        e.toString());
            }
        }

        if (firstJobId != null) {
            settleTriggered(key, firstJobId);
        } else {
            settleRefused(key, firstRefusal != null ? firstRefusal : SignalStateReason.SUBJECT_UNLINKED);
        }
        return started;
    }

    private ConversationReviewSubmissionRequest requestFor(ConversationThreadCandidate candidate, long participant) {
        return new ConversationReviewSubmissionRequest(
                candidate.threadId(),
                candidate.channelId(),
                candidate.channelName(),
                candidate.threadTs(),
                participant,
                candidate.lastTs());
    }

    private void settleTriggered(SignalKey key, UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> signalRecorder.markTriggered(key, jobId));
    }

    private void settleRefused(SignalKey key, SignalStateReason reason) {
        transactionTemplate.executeWithoutResult(status -> signalRecorder.markRefused(key, reason));
    }
}
