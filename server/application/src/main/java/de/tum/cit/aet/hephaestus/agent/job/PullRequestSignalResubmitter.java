package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers a pending pull-request signal back to the gate and the submission path.
 *
 * <p>Deliberately replays the whole decision rather than the refusal that blocked it: the workspace
 * may have changed its mind about drafts, or retired the practice, in the time the signal waited. If
 * it still refuses, the submission path records the current reason, which restamps the wait and
 * therefore paces the next attempt.
 */
@Component
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
public class PullRequestSignalResubmitter implements PendingSignalResubmitter {

    private static final Logger log = LoggerFactory.getLogger(PullRequestSignalResubmitter.class);

    private final AgentJobService agentJobService;
    private final PullRequestRepository pullRequestRepository;
    private final PracticeReviewDetectionGate practiceReviewDetectionGate;
    private final SignalRecorder signalRecorder;
    private final PullRequestReviewRepository reviewRepository;

    public PullRequestSignalResubmitter(
            AgentJobService agentJobService,
            PullRequestRepository pullRequestRepository,
            PracticeReviewDetectionGate practiceReviewDetectionGate,
            SignalRecorder signalRecorder,
            PullRequestReviewRepository reviewRepository) {
        this.agentJobService = agentJobService;
        this.pullRequestRepository = pullRequestRepository;
        this.practiceReviewDetectionGate = practiceReviewDetectionGate;
        this.signalRecorder = signalRecorder;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public ArtifactKind artifactKind() {
        return ScmSignals.PULL_REQUEST;
    }

    /**
     * Its own transaction so one signal's failure cannot unwind the rest of the sweep, and so the
     * submission path's idempotency-race rollback stays confined to this signal.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resubmit(ArtifactSignal signal) {
        SignalKey key = signal.key();
        PullRequest pr =
                pullRequestRepository.findByIdWithAllForGate(key.artifactId()).orElse(null);
        if (pr != null && pr.getDeletedAt() != null) {
            log.debug("Pending signal's pull request is not visible upstream: prId={}", pr.getId());
            signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_NOT_VISIBLE);
            return;
        }
        if (pr == null || pr.getHeadRefName() == null || pr.getHeadRefOid() == null || pr.getBaseRefName() == null) {
            log.debug("Pending signal has no reviewable pull request left: prId={}", key.artifactId());
            signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_GONE);
            return;
        }

        // The subject of a submitted-review occasion is the reviewer, and the only thing that still knows
        // which review that was is the occasion's own revision: the pull request has since moved on and
        // its author is a different person.
        ScmEventPayload.@Nullable ReviewData reviewData = null;
        if (key.signalName().equals(ScmSignals.PULL_REQUEST_REVIEWED)) {
            reviewData = key.revision()
                    .eventId()
                    .flatMap(reviewId -> reviewRepository.findByIdAndPullRequestId(reviewId, pr.getId()))
                    .flatMap(ScmEventPayload.ReviewData::from)
                    .orElse(null);
            if (reviewData == null) {
                log.debug("Pending signal's review no longer belongs to its pull request: prId={}", pr.getId());
                signalRecorder.markRefused(key, SignalStateReason.ARTIFACT_GONE);
                return;
            }
            if (reviewData.authorId() == null) {
                log.debug("Pending signal's review has no linked reviewer: reviewId={}", reviewData.id());
                signalRecorder.markRefused(key, SignalStateReason.SUBJECT_UNLINKED);
                return;
            }
        }

        GateDecision decision = reviewData == null
                ? practiceReviewDetectionGate.evaluate(pr, key.signalName(), TriggerMode.AUTO)
                : practiceReviewDetectionGate.evaluate(
                        pr, key.signalName(), TriggerMode.AUTO, new ReviewSubject(reviewData.authorId(), true));
        switch (decision) {
            case GateDecision.Skip skip -> {
                log.debug("Pending signal now skipped by practice gate: prId={}, reason={}", pr.getId(), skip.reason());
                signalRecorder.markRefused(key, skip.resolvedSignalReason());
            }
            case GateDecision.Detect detect -> {
                ScmEventPayload.PullRequestData prData = ScmEventPayload.PullRequestData.from(pr);
                PullRequestReviewSubmissionRequest request = reviewData == null
                        ? new PullRequestReviewSubmissionRequest(
                                prData, pr.getHeadRefName(), pr.getHeadRefOid(), pr.getBaseRefName(), key.signalName())
                        : PullRequestReviewSubmissionRequest.forSubmittedReview(
                                prData,
                                pr.getHeadRefName(),
                                pr.getHeadRefOid(),
                                pr.getBaseRefName(),
                                key.signalName(),
                                reviewData);
                agentJobService.submit(
                        detect.workspace().getId(),
                        AgentJobType.PULL_REQUEST_REVIEW,
                        // Carried from the ledger row rather than defaulted: a re-offered signal keeps the
                        // population it was discovered for, so a campaign's budget-deferred tail cannot land
                        // in the live series hours after the campaign paused.
                        request.withOrigin(SignalOrigins.observationOriginOf(signal.getDiscoveredVia())),
                        key,
                        detect);
            }
        }
    }
}
