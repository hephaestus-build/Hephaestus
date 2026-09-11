package de.tum.cit.aet.hephaestus.agent.backfill;

import static de.tum.cit.aet.hephaestus.practices.review.GateDecisionTestFixtures.automaticDetection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

/** One artifact's turn in a campaign: what it records, what it stamps, and when it declines to act. */
@DisplayName("Review backfill submitter")
class ReviewBackfillSubmitterTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 3L;
    private static final long PR_ID = 88L;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private PracticeReviewDetectionGate detectionGate;

    @Mock
    private SignalRecorder signalRecorder;

    private ReviewBackfillSubmitter submitter() {
        return new ReviewBackfillSubmitter(
                agentJobService, pullRequestRepository, issueRepository, detectionGate, signalRecorder);
    }

    /**
     * The two stamps that keep a campaign's output separable forever after: the ledger row says it was
     * discovered by a campaign, and the submission says its measurements belong to the backfill
     * population. Both are explicit because nothing downstream can re-derive either.
     */
    @Test
    void aSubmittedReviewIsStampedAsABackfillOnBothTheLedgerAndTheMeasurement() {
        PullRequest pr = mergedPullRequest();
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(signalRecorder.record(any(), any(), eq(DiscoveredVia.BACKFILL))).thenReturn(true);
        Workspace workspace = workspace();
        when(detectionGate.evaluate(eq(pr), any(), eq(TriggerMode.MANUAL)))
                .thenReturn(automaticDetection(workspace, List.of()));

        assertThat(submitter().offer(run(), PR_ID)).isEqualTo(ReviewBackfillSubmitter.Outcome.SUBMITTED);

        ArgumentCaptor<PullRequestReviewSubmissionRequest> request =
                ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
        verify(agentJobService)
                .submit(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.PULL_REQUEST_REVIEW),
                        request.capture(),
                        any(SignalKey.class),
                        any());
        assertThat(request.getValue().observationOrigin()).isEqualTo(ObservationOrigin.BACKFILL);
    }

    /**
     * The same two stamps, taken from the run instead of written here.
     *
     * <p>A sweep's window is bounded to the recent past, so it measures the population events measure and
     * its findings belong in the live trend line.
     */
    @Test
    void aScheduledSweepIsStampedAsASweepAndMeasuredAsLiveWork() {
        PullRequest pr = mergedPullRequest();
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(signalRecorder.record(any(), any(), eq(DiscoveredVia.SWEEP))).thenReturn(true);
        when(detectionGate.evaluate(eq(pr), any(), eq(TriggerMode.MANUAL)))
                .thenReturn(automaticDetection(workspace(), List.of()));
        ReviewBackfillRun run = run();
        run.setDiscoveredVia(DiscoveredVia.SWEEP);

        assertThat(submitter().offer(run, PR_ID)).isEqualTo(ReviewBackfillSubmitter.Outcome.SUBMITTED);

        verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SWEEP));
        ArgumentCaptor<PullRequestReviewSubmissionRequest> request =
                ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
        verify(agentJobService).submit(eq(WORKSPACE_ID), any(), request.capture(), any(SignalKey.class), any());
        assertThat(request.getValue().observationOrigin()).isEqualTo(ObservationOrigin.LIVE);
    }

    /**
     * Already settled — the live path measured this occurrence, or an earlier batch did. Nothing new to
     * say and nothing to pay for.
     */
    @Test
    void anOccurrenceSomeoneElseAlreadySettledIsWalkedPast() {
        PullRequest pr = mergedPullRequest();
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(signalRecorder.record(any(), any(), eq(DiscoveredVia.BACKFILL))).thenReturn(false);

        assertThat(submitter().offer(run(), PR_ID)).isEqualTo(ReviewBackfillSubmitter.Outcome.PASSED);

        Mockito.verifyNoInteractions(detectionGate, agentJobService);
    }

    @Test
    void theGateIsAskedInManualModeBecauseNoEventOccasionedThis() {
        PullRequest pr = mergedPullRequest();
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(signalRecorder.record(any(), any(), eq(DiscoveredVia.BACKFILL))).thenReturn(true);
        when(detectionGate.evaluate(eq(pr), any(), eq(TriggerMode.MANUAL)))
                .thenReturn(new GateDecision.Skip("manual trigger disabled for workspace"));

        assertThat(submitter().offer(run(), PR_ID)).isEqualTo(ReviewBackfillSubmitter.Outcome.PASSED);
        verify(signalRecorder).markRefused(any(), eq(SignalStateReason.GATE_SKIPPED));
        verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
    }

    /** No branch refs, nothing to clone or diff — there was never a reviewable artifact to leave a gap. */
    @Test
    void anArtifactWithNothingReviewableLeftIsNotRecordedAtAll() {
        PullRequest pr = mergedPullRequest();
        pr.setHeadRefOid(null);
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

        assertThat(submitter().offer(run(), PR_ID)).isEqualTo(ReviewBackfillSubmitter.Outcome.PASSED);
        Mockito.verifyNoInteractions(signalRecorder, detectionGate, agentJobService);
    }

    @Test
    void anArtifactTheMirrorNoLongerHoldsIsWalkedPast() {
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.empty());

        assertThat(submitter().offer(run(), PR_ID)).isEqualTo(ReviewBackfillSubmitter.Outcome.PASSED);
        Mockito.verifyNoInteractions(signalRecorder, detectionGate, agentJobService);
    }

    private PullRequest mergedPullRequest() {
        Repository repository = new Repository();
        repository.setId(11L);
        repository.setNameWithOwner("acme/widgets");

        PullRequest pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setNumber(7);
        pr.setState(Issue.State.MERGED);
        pr.setMerged(true);
        pr.setTitle("Add a thing");
        pr.setBody("because reasons");
        pr.setHtmlUrl("https://example.test/acme/widgets/pull/7");
        pr.setHeadRefName("feature/thing");
        pr.setBaseRefName("main");
        pr.setBaseRefOid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        pr.setHeadRefOid("0123456789abcdef0123456789abcdef01234567");
        pr.setRepository(repository);
        pr.setCreatedAt(Instant.parse("2026-07-02T00:00:00Z"));
        pr.setUpdatedAt(Instant.parse("2026-07-09T00:00:00Z"));
        return pr;
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        return workspace;
    }

    private ReviewBackfillRun run() {
        ReviewBackfillRun run = new ReviewBackfillRun();
        run.setId(UUID.randomUUID());
        run.setWorkspace(workspace());
        run.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        run.setFromAt(Instant.parse("2026-07-01T00:00:00Z"));
        run.setToAt(Instant.parse("2026-08-01T00:00:00Z"));
        run.setStatus(ReviewBackfillStatus.RUNNING);
        return run;
    }
}
