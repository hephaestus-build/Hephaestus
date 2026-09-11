package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@Tag("unit")
class ScmSignalResubmitterTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long ARTIFACT_ID = 41L;
    private static final long REVIEW_ID = 99L;
    private static final long REVIEWER_ID = 123L;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PullRequestReviewRepository reviewRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private PracticeReviewDetectionGate gate;

    @Mock
    private SignalRecorder signalRecorder;

    private Workspace workspace;
    private Repository repository;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        repository = new Repository();
        repository.setId(11L);
        repository.setNameWithOwner("hephaestus-build/Hephaestus");
        repository.setDefaultBranch("main");
    }

    @Test
    void shouldHoldPullRequestSignalWhileTombstoned() {
        PullRequest pullRequest = pullRequest();
        pullRequest.setDeletedAt(Instant.now());
        ArtifactSignal signal = signal(ScmSignals.PULL_REQUEST_OPENED.value());
        when(pullRequestRepository.findByIdWithAllForGate(ARTIFACT_ID)).thenReturn(Optional.of(pullRequest));

        pullRequestResubmitter().resubmit(signal);

        verify(signalRecorder).markRefused(signal.key(), SignalStateReason.ARTIFACT_NOT_VISIBLE);
        verify(gate, never()).evaluate(any(), any(), any());
        verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void shouldHoldIssueSignalWhileTombstoned() {
        Issue issue = issue();
        issue.setDeletedAt(Instant.now());
        ArtifactSignal signal = signal(ScmSignals.ISSUE_OPENED.value());
        when(issueRepository.findByIdWithRepositoryAndAssignees(ARTIFACT_ID)).thenReturn(Optional.of(issue));

        new IssueSignalResubmitter(agentJobService, issueRepository, gate, signalRecorder).resubmit(signal);

        verify(signalRecorder).markRefused(signal.key(), SignalStateReason.ARTIFACT_NOT_VISIBLE);
        verify(gate, never()).evaluateIssue(any(), any(), any());
        verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void shouldSubmitPullRequestSignalWhenTheArtifactIsVisible() {
        ArtifactSignal signal = signal(ScmSignals.PULL_REQUEST_OPENED.value());
        PullRequest pullRequest = pullRequest();
        GateDecision.Detect detection = detection();
        when(pullRequestRepository.findByIdWithAllForGate(ARTIFACT_ID)).thenReturn(Optional.of(pullRequest));
        when(gate.evaluate(pullRequest, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                .thenReturn(detection);

        pullRequestResubmitter().resubmit(signal);

        verify(agentJobService)
                .submit(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.PULL_REQUEST_REVIEW),
                        any(PullRequestReviewSubmissionRequest.class),
                        eq(signal.key()),
                        eq(detection));
        verify(signalRecorder, never()).markRefused(any(), any());
    }

    @Test
    void shouldSubmitIssueSignalWhenTheArtifactIsVisible() {
        ArtifactSignal signal = signal(ScmSignals.ISSUE_OPENED.value());
        Issue issue = issue();
        GateDecision.Detect detection = detection();
        when(issueRepository.findByIdWithRepositoryAndAssignees(ARTIFACT_ID)).thenReturn(Optional.of(issue));
        when(gate.evaluateIssue(issue, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO))
                .thenReturn(detection);

        new IssueSignalResubmitter(agentJobService, issueRepository, gate, signalRecorder).resubmit(signal);

        verify(agentJobService)
                .submit(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.ISSUE_REVIEW),
                        any(IssueReviewSubmissionRequest.class),
                        eq(signal.key()),
                        eq(detection));
        verify(signalRecorder, never()).markRefused(any(), any());
    }

    @Test
    void shouldNotRetryAnUpdateAgainstADifferentIssueSnapshot() {
        ArtifactSignal signal = signal(ScmSignals.ISSUE_UPDATED.value());
        when(issueRepository.findByIdWithRepositoryAndAssignees(ARTIFACT_ID)).thenReturn(Optional.of(issue()));
        new IssueSignalResubmitter(agentJobService, issueRepository, gate, signalRecorder).resubmit(signal);
        verify(signalRecorder).markRefused(signal.key(), SignalStateReason.COALESCED);
        verify(gate, never()).evaluateIssue(any(), any(), any());
        verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void shouldKeepTheReviewerAsTheSubjectOfARetriedSubmittedReviewOccasion() {
        PullRequest pullRequest = pullRequest();
        ArtifactSignal signal = signal(ScmSignals.PULL_REQUEST_REVIEWED.value());
        signal.setRevision(SignalRevision.ofEventId(REVIEW_ID).value());
        when(pullRequestRepository.findByIdWithAllForGate(ARTIFACT_ID)).thenReturn(Optional.of(pullRequest));
        when(reviewRepository.findByIdAndPullRequestId(REVIEW_ID, ARTIFACT_ID)).thenReturn(Optional.of(review()));
        GateDecision.Detect detection = detection();
        when(gate.evaluate(
                        pullRequest,
                        ScmSignals.PULL_REQUEST_REVIEWED,
                        TriggerMode.AUTO,
                        new ReviewSubject(REVIEWER_ID, true)))
                .thenReturn(detection);

        pullRequestResubmitter().resubmit(signal);

        var request = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
        verify(agentJobService)
                .submit(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.PULL_REQUEST_REVIEW),
                        request.capture(),
                        eq(signal.key()),
                        eq(detection));
        assertThat(request.getValue().reviewId()).isEqualTo(REVIEW_ID);
        assertThat(request.getValue().aboutUserId()).isEqualTo(REVIEWER_ID);
        verify(signalRecorder, never()).markRefused(any(), any());
    }

    @Test
    void shouldRetireASubmittedReviewOccasionWhoseReviewNoLongerBelongsToItsPullRequest() {
        ArtifactSignal signal = signal(ScmSignals.PULL_REQUEST_REVIEWED.value());
        signal.setRevision(SignalRevision.ofEventId(REVIEW_ID).value());
        when(pullRequestRepository.findByIdWithAllForGate(ARTIFACT_ID)).thenReturn(Optional.of(pullRequest()));
        when(reviewRepository.findByIdAndPullRequestId(REVIEW_ID, ARTIFACT_ID)).thenReturn(Optional.empty());

        pullRequestResubmitter().resubmit(signal);

        verify(signalRecorder).markRefused(signal.key(), SignalStateReason.ARTIFACT_GONE);
        verifyNoInteractions(gate, agentJobService);
    }

    @Test
    void shouldHoldASubmittedReviewOccasionWhoseReviewerIsNotLinkedYet() {
        ArtifactSignal signal = signal(ScmSignals.PULL_REQUEST_REVIEWED.value());
        signal.setRevision(SignalRevision.ofEventId(REVIEW_ID).value());
        PullRequestReview review = review();
        review.setAuthor(null);
        when(pullRequestRepository.findByIdWithAllForGate(ARTIFACT_ID)).thenReturn(Optional.of(pullRequest()));
        when(reviewRepository.findByIdAndPullRequestId(REVIEW_ID, ARTIFACT_ID)).thenReturn(Optional.of(review));

        pullRequestResubmitter().resubmit(signal);

        verify(signalRecorder).markRefused(signal.key(), SignalStateReason.SUBJECT_UNLINKED);
        verifyNoInteractions(gate, agentJobService);
    }

    private PullRequestSignalResubmitter pullRequestResubmitter() {
        return new PullRequestSignalResubmitter(
                agentJobService, pullRequestRepository, gate, signalRecorder, reviewRepository);
    }

    private GateDecision.Detect detection() {
        return new GateDecision.Detect(workspace, List.of(), 1, TriggerMode.AUTO);
    }

    private ArtifactSignal signal(String name) {
        ArtifactSignal signal = new ArtifactSignal();
        signal.setWorkspace(workspace);
        signal.setArtifactId(ARTIFACT_ID);
        signal.setSignalName(name);
        signal.setRevision("revision");
        signal.setDiscoveredVia(DiscoveredVia.EVENT);
        return signal;
    }

    private PullRequest pullRequest() {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(ARTIFACT_ID);
        pullRequest.setRepository(repository);
        pullRequest.setTitle("Raise the bar");
        pullRequest.setState(Issue.State.OPEN);
        pullRequest.setHeadRefName("feature");
        pullRequest.setHeadRefOid("abc123");
        pullRequest.setBaseRefName("main");
        return pullRequest;
    }

    private PullRequestReview review() {
        User reviewer = new User();
        reviewer.setId(REVIEWER_ID);
        PullRequestReview review = new PullRequestReview();
        review.setId(REVIEW_ID);
        review.setPullRequest(pullRequest());
        review.setAuthor(reviewer);
        review.setState(PullRequestReview.State.APPROVED);
        return review;
    }

    private Issue issue() {
        Issue issue = new Issue();
        issue.setId(ARTIFACT_ID);
        issue.setRepository(repository);
        issue.setNumber(1806);
        issue.setTitle("Keep pending reviews recoverable");
        issue.setState(Issue.State.OPEN);
        return issue;
    }
}
