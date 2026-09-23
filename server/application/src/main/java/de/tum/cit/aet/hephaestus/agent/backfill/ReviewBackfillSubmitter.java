package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.agent.job.SignalOrigins;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers one already-existing artifact to the review path on a campaign's behalf, running the same steps
 * the live ingestion path runs — record the signal, ask the gate, submit.
 *
 * <p>The signal is recorded with the run's own {@link DiscoveredVia} (from
 * {@link ReviewBackfillRun#getDiscoveredVia()}, mapped once in {@code SignalOrigins}), and the submission
 * states its {@link ObservationOrigin} explicitly, so a hand-scoped campaign (BACKFILL) and a
 * {@link ReviewSweepSchedule}'s recurring run (SWEEP) each stay filed against the population they measure
 * rather than blending into the health metrics the event path watches.
 *
 * <p>The gate is asked in {@link TriggerMode#MANUAL} mode — no event occasioned this review, a person
 * did — so a workspace with manual triggering off cannot be backfilled.
 *
 * <p>Its own transaction per artifact, like {@code PullRequestSignalResubmitter}: one artifact's failure
 * must not unwind the batch around it.
 */
@Component
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
public class ReviewBackfillSubmitter {

    private static final Logger log = LoggerFactory.getLogger(ReviewBackfillSubmitter.class);

    public enum Outcome {
        SUBMITTED,
        /**
         * Nothing was submitted, and nothing is owed. The artifact was already measured at its current
         * state, has nothing reviewable left, or the gate refused it on a standing workspace policy.
         */
        PASSED,
    }

    private final AgentJobService agentJobService;
    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final PracticeReviewDetectionGate detectionGate;
    private final SignalRecorder signalRecorder;

    public ReviewBackfillSubmitter(
            AgentJobService agentJobService,
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            PracticeReviewDetectionGate detectionGate,
            SignalRecorder signalRecorder) {
        this.agentJobService = agentJobService;
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.detectionGate = detectionGate;
        this.signalRecorder = signalRecorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome offer(ReviewBackfillRun run, long artifactId) {
        return ArtifactKinds.PULL_REQUEST.equals(run.kind())
                ? offerPullRequest(run, artifactId)
                : offerIssue(run, artifactId);
    }

    private Outcome offerPullRequest(ReviewBackfillRun run, long artifactId) {
        PullRequest pr =
                pullRequestRepository.findByIdWithAllForGate(artifactId).orElse(null);
        if (pr == null || pr.getHeadRefName() == null || pr.getHeadRefOid() == null || pr.getBaseRefName() == null) {
            // No branch refs means nothing to clone or diff: there was never a reviewable artifact here.
            return Outcome.PASSED;
        }
        long workspaceId = run.getWorkspace().getId();
        Optional<SignalKey> key = ReviewBackfillSignals.keyFor(workspaceId, pr);
        if (key.isEmpty() || !claim(run, key.get(), occurredAt(pr.getUpdatedAt(), pr.getCreatedAt()))) {
            return Outcome.PASSED;
        }

        switch (detectionGate.evaluate(pr, key.get().signalName(), TriggerMode.MANUAL)) {
            case GateDecision.Skip skip -> {
                log.debug("Backfill skipped by practice gate: prId={}, reason={}", pr.getId(), skip.reason());
                signalRecorder.markRefused(key.get(), skip.resolvedSignalReason());
                return Outcome.PASSED;
            }
            case GateDecision.Detect detect -> {
                agentJobService.submit(
                        detect.workspace().getId(),
                        AgentJobType.PULL_REQUEST_REVIEW,
                        new PullRequestReviewSubmissionRequest(
                                ScmEventPayload.PullRequestData.from(pr),
                                pr.getHeadRefName(),
                                pr.getHeadRefOid(),
                                pr.getBaseRefName(),
                                pr.getBaseRefOid(),
                                key.get().signalName(),
                                SignalOrigins.observationOriginOf(run.getDiscoveredVia())),
                        key.get(),
                        detect);
                return Outcome.SUBMITTED;
            }
        }
    }

    private Outcome offerIssue(ReviewBackfillRun run, long artifactId) {
        Issue issue =
                issueRepository.findByIdWithRepositoryAndAssignees(artifactId).orElse(null);
        if (issue == null || issue.getRepository() == null) {
            return Outcome.PASSED;
        }
        long workspaceId = run.getWorkspace().getId();
        Optional<SignalKey> key = ReviewBackfillSignals.keyFor(workspaceId, issue);
        if (key.isEmpty() || !claim(run, key.get(), occurredAt(issue.getUpdatedAt(), issue.getCreatedAt()))) {
            return Outcome.PASSED;
        }

        switch (detectionGate.evaluateIssue(issue, key.get().signalName(), TriggerMode.MANUAL)) {
            case GateDecision.Skip skip -> {
                log.debug("Backfill skipped by practice gate: issueId={}, reason={}", issue.getId(), skip.reason());
                signalRecorder.markRefused(key.get(), skip.resolvedSignalReason());
                return Outcome.PASSED;
            }
            case GateDecision.Detect detect -> {
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
                                key.get().signalName(),
                                SignalOrigins.observationOriginOf(run.getDiscoveredVia())),
                        key.get(),
                        detect);
                return Outcome.SUBMITTED;
            }
        }
    }

    /**
     * Whether this campaign now owns the occurrence and may act on it. This one call is the whole spend
     * guard for a recurring sweep: {@link ReviewBackfillSignals} derives the key from the artifact's
     * current state, so a second sweep over an untouched artifact produces the identical key,
     * {@code uq_artifact_signal} refuses the insert, and this returns false.
     */
    private boolean claim(ReviewBackfillRun run, SignalKey key, Instant occurredAt) {
        return signalRecorder.record(key, occurredAt, run.getDiscoveredVia());
    }

    /**
     * When the artifact reached the state about to be measured. The last update is only an upper bound —
     * the limit of every non-live discovery: the mirror knows the artifact is in this state, not when it
     * got there.
     */
    private static Instant occurredAt(@Nullable Instant updatedAt, @Nullable Instant createdAt) {
        if (updatedAt != null) {
            return updatedAt;
        }
        return createdAt != null ? createdAt : Instant.now();
    }
}
