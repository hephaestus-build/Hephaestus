package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.practices.review.GateDecisionTestFixtures.automaticDetection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class DevTriggerControllerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long PR_ID = 5L;
    private static final long ISSUE_ID = 7L;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private ReviewableArtifactLoader artifactLoader;

    @Mock
    private PracticeReviewDetectionGate detectionGate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private SignalRecorder signalRecorder;

    private DevTriggerController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new DevTriggerController(
                agentJobService, artifactLoader, detectionGate, transactionTemplate, signalRecorder);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void shouldRecordAPullRequestGateRefusalAgainstItsSignal() {
        PullRequest pr = pullRequest();
        when(artifactLoader.findPullRequestForGate(WORKSPACE_ID, PR_ID)).thenReturn(Optional.of(pr));
        when(detectionGate.evaluate(any(), any(), any())).thenReturn(new GateDecision.Skip("no assignee"));

        String response = controller.triggerReview(PR_ID, null, WORKSPACE_ID, "scm.pull_request.merged");

        assertThat(response).contains("Gate skipped");
        verify(signalRecorder).markRefused(expectedPullRequestKey(), SignalStateReason.GATE_SKIPPED);
    }

    @Test
    void shouldRecordTheGatesOwnReasonWhenItNamesOne() {
        PullRequest pr = pullRequest();
        when(artifactLoader.findPullRequestForGate(WORKSPACE_ID, PR_ID)).thenReturn(Optional.of(pr));
        when(detectionGate.evaluate(any(), any(), any()))
                .thenReturn(new GateDecision.Skip(
                        "nobody it could be attributed to is linked", SignalStateReason.SUBJECT_UNLINKED));

        controller.triggerReview(PR_ID, null, WORKSPACE_ID, "scm.pull_request.merged");

        // SUBJECT_UNLINKED is retryable and GATE_SKIPPED is not; flattening the two would retire a
        // signal that linking an account would revive.
        verify(signalRecorder).markRefused(expectedPullRequestKey(), SignalStateReason.SUBJECT_UNLINKED);
    }

    @Test
    void shouldRecordAnIssueGateRefusalAgainstItsSignal() {
        Issue issue = issue();
        when(artifactLoader.findIssueForGate(WORKSPACE_ID, ISSUE_ID)).thenReturn(Optional.of(issue));
        when(agentJobService.buildIssueRequest(any(), any())).thenReturn(issueRequest());
        when(detectionGate.evaluateIssue(any(), any(), any())).thenReturn(new GateDecision.Skip("no assignee"));

        String response = controller.triggerReview(null, ISSUE_ID, WORKSPACE_ID, "scm.issue.closed");

        assertThat(response).contains("Gate skipped");
        verify(signalRecorder)
                .markRefused(
                        ScmSignals.issueKey(
                                        WORKSPACE_ID, ScmSignals.ISSUE_CLOSED, ScmEventPayload.IssueData.from(issue))
                                .orElseThrow(),
                        SignalStateReason.GATE_SKIPPED);
    }

    @Test
    void shouldSettleNothingWhenTheGatePasses() {
        PullRequest pr = pullRequest();
        when(artifactLoader.findPullRequestForGate(WORKSPACE_ID, PR_ID)).thenReturn(Optional.of(pr));
        when(detectionGate.evaluate(any(), any(), any())).thenReturn(automaticDetection(new Workspace(), List.of()));
        when(agentJobService.buildReviewRequest(any(), any())).thenReturn(null);

        controller.triggerReview(PR_ID, null, WORKSPACE_ID, "scm.pull_request.merged");

        verifyNoInteractions(signalRecorder);
    }

    @Test
    void shouldSettleNothingWhenTheSignalHasNoStableIdentityYet() {
        // Keyed on the head commit; minting a ledger row without one would assert an occurrence
        // nobody observed.
        PullRequest pr = pullRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(pr, "headRefOid", null);
        when(artifactLoader.findPullRequestForGate(WORKSPACE_ID, PR_ID)).thenReturn(Optional.of(pr));
        when(detectionGate.evaluate(any(), any(), any())).thenReturn(new GateDecision.Skip("no assignee"));

        String response = controller.triggerReview(PR_ID, null, WORKSPACE_ID, "scm.pull_request.synchronized");

        assertThat(response).contains("Gate skipped");
        verify(signalRecorder, never()).markRefused(any(), any());
    }

    private static SignalKey expectedPullRequestKey() {
        return ScmSignals.pullRequestKey(
                        WORKSPACE_ID, PR_ID, ScmSignals.PULL_REQUEST_MERGED, "abc123", "Add a thing", "Because.")
                .orElseThrow();
    }

    private static PullRequest pullRequest() {
        PullRequest pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setTitle("Add a thing");
        pr.setBody("Because.");
        pr.setHeadRefOid("abc123");
        pr.setHeadRefName("feature/thing");
        pr.setBaseRefName("main");
        pr.setBaseRefOid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Repository repository = new Repository();
        repository.setId(100L);
        repository.setNameWithOwner("owner/repo");
        pr.setRepository(repository);
        return pr;
    }

    /**
     * An issue whose repository the mirror never resolved is not reviewable, and a signal key cannot be
     * built for one — so the endpoint has to answer that before it asks the gate anything, rather than
     * settling a ledger row for an artifact nobody can fetch.
     */
    @Test
    void shouldRefuseAnIssueWithNoRepositoryBeforeConsultingTheGate() {
        Issue issue = issue();
        org.springframework.test.util.ReflectionTestUtils.setField(issue, "repository", null);
        when(artifactLoader.findIssueForGate(WORKSPACE_ID, ISSUE_ID)).thenReturn(Optional.of(issue));
        when(agentJobService.buildIssueRequest(any(), any())).thenReturn(null);

        String response = controller.triggerReview(null, ISSUE_ID, WORKSPACE_ID, "scm.issue.closed");

        assertThat(response).contains("Issue missing repository");
        verify(detectionGate, never()).evaluateIssue(any(), any(), any());
        verifyNoInteractions(signalRecorder);
    }

    private static IssueReviewSubmissionRequest issueRequest() {
        return new IssueReviewSubmissionRequest(
                ISSUE_ID,
                3,
                100L,
                "owner/repo",
                "Something broke",
                "Here is how.",
                "CLOSED",
                "https://github.com/owner/repo/issues/3",
                Instant.parse("2026-08-07T10:00:00Z"),
                ScmSignals.ISSUE_CLOSED);
    }

    private static Issue issue() {
        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setTitle("Something broke");
        issue.setBody("Here is how.");
        issue.setState(Issue.State.CLOSED);
        issue.setUpdatedAt(Instant.parse("2026-08-07T10:00:00Z"));
        issue.setClosedAt(Instant.parse("2026-08-07T10:00:00Z"));
        Repository repository = new Repository();
        repository.setId(100L);
        repository.setNameWithOwner("owner/repo");
        issue.setRepository(repository);
        return issue;
    }
}
