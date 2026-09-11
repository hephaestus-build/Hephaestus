package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single path every hand-requested review takes: the gate is asked about the request signal itself,
 * the run is filed as a self-selected sample, and every ask leaves a ledger row.
 */
@Tag("unit")
@DisplayName("A review somebody asked for")
class ManualReviewRequestsTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long PR_ID = 500L;
    private static final long REQUESTER_ID = 4242L;

    @Mock
    private ReviewRequestAuthority authority;

    @Mock
    private ManualReviewRateLimits rateLimits;

    @Mock
    private PracticeReviewDetectionGate gate;

    @Mock
    private PracticeSignalOptions signalOptions;

    @Mock
    private de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder signalRecorder;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ManualReviewRequests requests;
    private Workspace workspace;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        requests = new ManualReviewRequests(
                authority, rateLimits, gate, signalOptions, signalRecorder, agentJobService, transactionTemplate);
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        lenient()
                .doAnswer(invocation -> {
                    ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        lenient()
                .when(authority.standingOf(anyLong(), any(), any()))
                .thenAnswer(inv -> requesters().stream().findFirst());
        lenient().when(rateLimits.refusalFor(any(), any(), anyLong(), any())).thenReturn(Optional.empty());
        lenient()
                .when(signalOptions.manualRequestSignalFor(ScmSignals.PULL_REQUEST))
                .thenReturn(Optional.of(ScmSignals.PULL_REQUEST_MANUAL_REVIEW));
    }

    @Test
    void anAskFromSomebodyWithNoStandingSpendsNothingAndRecordsNothing() {
        when(authority.standingOf(anyLong(), any(), any())).thenReturn(Optional.empty());

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.FORBIDDEN);
        verifyNoInteractions(signalRecorder, gate, agentJobService);
    }

    /**
     * The gate is asked about the occasion that actually happened — naming a lifecycle event instead
     * puts an untruth in the artifact trace, which renders the signal as the reason the review ran.
     */
    @Test
    void theGateIsAskedAboutTheRequest_notAboutALifecycleEventThatDidNotHappen() {
        givenGateDetects();
        givenSubmissionSucceeds();

        requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        verify(gate).evaluate(any(), eq(ScmSignals.PULL_REQUEST_MANUAL_REVIEW), eq(TriggerMode.MANUAL));
        verify(gate, never()).evaluate(any(), eq(ScmSignals.PULL_REQUEST_OPENED), any());
    }

    /**
     * Two facts that have to hold together. The metadata signal is null, which is what makes the run
     * load every active practice of the kind rather than the none that bind a request signal; and the
     * origin is stated rather than derived, so that null cannot later be read as LIVE.
     */
    @Test
    void theRunCarriesNoOccasionInItsMetadataAndIsFiledAsASelfSelectedSample() {
        givenGateDetects();
        givenSubmissionSucceeds();

        requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        var captor = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
        verify(agentJobService)
                .submitWithOutcome(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.PULL_REQUEST_REVIEW),
                        captor.capture(),
                        any(),
                        any(GateDecision.Detect.class));
        assertThat(captor.getValue().triggerSignal()).isNull();
        assertThat(captor.getValue().observationOrigin()).isEqualTo(ObservationOrigin.MANUAL);
    }

    @Test
    void theAskIsRecordedInTheLedgerUnderItsOwnRunId() {
        givenGateDetects();
        givenSubmissionSucceeds();

        requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        var captor = ArgumentCaptor.forClass(SignalKey.class);
        verify(signalRecorder).record(captor.capture(), any(), eq(DiscoveredVia.MANUAL), eq(REQUESTER_ID));
        SignalKey key = captor.getValue();
        assertThat(key.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(key.artifactId()).isEqualTo(PR_ID);
        assertThat(key.signalName()).isEqualTo(ScmSignals.PULL_REQUEST_MANUAL_REVIEW);
        assertThat(key.revision().scheme()).contains(RevisionScheme.RUN_ID);
    }

    /**
     * Two people asking is two occasions. If the second ask reused the first one's key the ledger's
     * unique constraint would swallow it, and the second person would be told nothing happened.
     */
    @Test
    void twoAsksAboutTheSameUnchangedWorkAreTwoOccasions() {
        givenGateDetects();
        givenSubmissionSucceeds();

        requests.requestPullRequestReview(workspace, pullRequest(), requesters());
        requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        var captor = ArgumentCaptor.forClass(SignalKey.class);
        verify(signalRecorder, org.mockito.Mockito.times(2))
                .record(captor.capture(), any(), eq(DiscoveredVia.MANUAL), eq(REQUESTER_ID));
        assertThat(captor.getAllValues().get(0))
                .isNotEqualTo(captor.getAllValues().get(1));
    }

    /** A refusal is settled against the ledger row and handed back as a sentence, not as a failure. */
    @Test
    void aGateRefusalIsRecordedAndExplained() {
        when(gate.evaluate(any(), any(), any()))
                .thenReturn(new GateDecision.Skip(
                        "every practice bound to this signal is off", SignalStateReason.PRACTICE_AUTONOMY_OFF));

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        assertThat(outcome.reason()).isEqualTo(SignalStateReason.PRACTICE_AUTONOMY_OFF);
        assertThat(outcome.describeReason()).isEqualTo(SignalStateReason.PRACTICE_AUTONOMY_OFF.describe());
        verify(signalRecorder).markRefused(any(), eq(SignalStateReason.PRACTICE_AUTONOMY_OFF));
        verify(agentJobService, never()).submitWithOutcome(any(), any(), any(), any(), any(GateDecision.Detect.class));
    }

    @Test
    void anAdministratorCanRequestAnInternalReviewOutsideCoverage() {
        GateDecision.Detect detection = new GateDecision.Detect(
                workspace,
                List.of(new Practice()),
                workspace.getReviewSettings().getRolloutRevision(),
                TriggerMode.MANUAL);
        when(gate.evaluate(any(), any(), any()))
                .thenReturn(new GateDecision.Skip("outside coverage", SignalStateReason.OUT_OF_REVIEW_SCOPE));
        when(authority.isWorkspaceAdmin(WORKSPACE_ID, REQUESTER_ID)).thenReturn(true);
        when(gate.evaluateAdministrative(any(), eq(ScmSignals.PULL_REQUEST_MANUAL_REVIEW)))
                .thenReturn(detection);
        givenSubmissionSucceeds();

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.SUBMITTED);
        verify(agentJobService).submitWithOutcome(anyLong(), any(), any(), any(), eq(detection));
    }

    @Test
    void anArtifactParticipantCannotBypassCoverage() {
        when(gate.evaluate(any(), any(), any()))
                .thenReturn(new GateDecision.Skip("outside coverage", SignalStateReason.OUT_OF_REVIEW_SCOPE));

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        verify(gate, never()).evaluateAdministrative(any(), any());
    }

    /** A submission refusal — an exhausted budget, a cooldown — travels out with its own reason too. */
    @Test
    void aSubmissionRefusalKeepsTheReasonTheSubmissionStoppedOn() {
        givenGateDetects();
        when(agentJobService.submitWithOutcome(anyLong(), any(), any(), any(), any(GateDecision.Detect.class)))
                .thenReturn(SubmissionOutcome.refused(SignalStateReason.BUDGET_EXHAUSTED));

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        assertThat(outcome.reason()).isEqualTo(SignalStateReason.BUDGET_EXHAUSTED);
    }

    /** A kind that never declared a request signal has no occasion to record this under. */
    @Test
    void aKindThatDeclaresNoRequestSignalRefusesRatherThanInventingOne() {
        when(signalOptions.manualRequestSignalFor(ScmSignals.PULL_REQUEST)).thenReturn(Optional.empty());

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        verifyNoInteractions(signalRecorder, agentJobService);
    }

    /**
     * A request that trips a limit leaves no ledger row. The limits count manual rows, so recording
     * refusals would make the population they count self-inflating: each declined ask would tighten the
     * allowance for the next one, and somebody who hit the limit once would be pushed further past it by
     * their own retries.
     */
    @Test
    void aRateLimitedAskSpendsNothingAndRecordsNothing() {
        when(rateLimits.refusalFor(any(), any(), anyLong(), any()))
                .thenReturn(Optional.of(SignalStateReason.REQUESTER_QUOTA_EXHAUSTED));

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        assertThat(outcome.reason()).isEqualTo(SignalStateReason.REQUESTER_QUOTA_EXHAUSTED);
        assertThat(outcome.describeReason()).isEqualTo(SignalStateReason.REQUESTER_QUOTA_EXHAUSTED.describe());
        verifyNoInteractions(signalRecorder, gate, agentJobService);
    }

    /**
     * Standing is established before the limits are consulted. The other order would let a stranger burn
     * a team's allowance simply by being refused over and over.
     */
    @Test
    void anAskFromSomebodyWithNoStandingNeverReachesTheLimits() {
        when(authority.standingOf(anyLong(), any(), any())).thenReturn(Optional.empty());

        requests.requestPullRequestReview(workspace, pullRequest(), requesters());

        verifyNoInteractions(rateLimits);
    }

    /** The limit is asked about every identity of the asker, not just the one that granted standing. */
    @Test
    void theLimitCountsEveryIdentityOfTheSamePerson() {
        givenGateDetects();
        givenSubmissionSucceeds();
        User second = new User();
        second.setId(7777L);

        requests.requestPullRequestReview(
                workspace, pullRequest(), List.of(requesters().get(0), second));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(rateLimits).refusalFor(eq(workspace), eq(ScmSignals.PULL_REQUEST), eq(PR_ID), ids.capture());
        assertThat(ids.getValue()).containsExactly(REQUESTER_ID, 7777L);
    }

    /** A mirror that has not caught up with the branch cannot be cloned or diffed. */
    @Test
    void aPullRequestWithNoHeadCommitIsRefusedBeforeAnythingIsRecorded() {
        PullRequest pr = pullRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(pr, "headRefOid", null);

        ManualReviewOutcome outcome = requests.requestPullRequestReview(workspace, pr, requesters());

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        assertThat(outcome.reason()).isEqualTo(SignalStateReason.ARTIFACT_GONE);
        verifyNoInteractions(signalRecorder, agentJobService);
    }

    // Fixtures

    private void givenGateDetects() {
        when(gate.evaluate(any(), any(), any()))
                .thenReturn(new GateDecision.Detect(
                        workspace,
                        List.of(new Practice()),
                        workspace.getReviewSettings().getRolloutRevision(),
                        TriggerMode.MANUAL));
    }

    private void givenSubmissionSucceeds() {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        when(agentJobService.submitWithOutcome(anyLong(), any(), any(), any(), any(GateDecision.Detect.class)))
                .thenReturn(SubmissionOutcome.created(job));
    }

    private PullRequest pullRequest() {
        Repository repo = new Repository();
        repo.setId(100L);
        repo.setNameWithOwner("hephaestustest/demo-repository");

        PullRequest pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setNumber(42);
        pr.setTitle("Test MR");
        pr.setState(PullRequest.State.OPEN);
        pr.setHeadRefOid("abc123");
        pr.setHeadRefName("feature/branch");
        pr.setBaseRefName("main");
        pr.setBaseRefOid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        pr.setRepository(repo);
        pr.setAssignees(Set.of());
        return pr;
    }

    private List<User> requesters() {
        User user = new User();
        user.setId(REQUESTER_ID);
        user.setLogin("student1");
        return List.of(user);
    }
}
