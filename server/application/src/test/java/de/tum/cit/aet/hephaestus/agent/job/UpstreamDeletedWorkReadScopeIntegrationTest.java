package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorContextQueryRepository;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two halves of the tombstone decision, on one pull request and one issue that are tombstoned and
 * then handed back by an ordinary upsert.
 *
 * <p>Heph must not describe work that no longer exists upstream, and a review somebody asks for must be
 * refused on it by name — so the mentor's queries and the project inventory drop the tombstoned row, and
 * the request path answers {@link SignalStateReason#ARTIFACT_GONE}. Everything the drift tombstone
 * promises to be able to undo must still see it: the upsert that resurrects it, and the gate loader the
 * pending-signal resubmitters read, which holds the occasion as {@link
 * SignalStateReason#ARTIFACT_NOT_VISIBLE} rather than retiring it. A live event reaching the listener
 * while the row is tombstoned takes the same hold, and the row the reaper would pick up is asserted
 * here rather than inferred.
 *
 * <p>Both kinds are exercised because {@code Issue} and {@code PullRequest} share one table and the
 * issue side additionally has to hold its {@code TYPE(i) = Issue} discriminator.
 *
 * <p>Runs against a live Postgres because most of the behaviour is a {@code WHERE} clause; the unit
 * tier mocks these repositories and would pass either way.
 */
class UpstreamDeletedWorkReadScopeIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final int PR_NUMBER = 42;
    private static final int ISSUE_NUMBER = 43;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
    private static final SignalRevision REVISION = new SignalRevision("read-scope-revision");

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private MentorContextQueryRepository mentorContextQueryRepository;

    @Autowired
    private ManualReviewRequests manualReviewRequests;

    @Autowired
    private ArtifactSignalRepository artifactSignalRepository;

    @Autowired
    private SignalRecorder signalRecorder;

    @Autowired
    private AgentJobService agentJobService;

    @Autowired
    private PracticeReviewDetectionGate gate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WorkspaceResolver workspaceResolver;

    private Workspace workspace;
    private User author;
    private Repository repository;
    private long pullRequestId;
    private long issueId;

    @BeforeEach
    void seedMonitoredWork() {
        User owner = persistUser("read-scope-owner");
        workspace = createWorkspace("read-scope-ws", "Read Scope WS", "read-scope-org", AccountType.ORG, owner);
        author = persistUser("read-scope-author");

        repository = new Repository();
        repository.setNativeId(9301L);
        repository.setProvider(ensureGitHubProvider());
        repository.setName("widgets");
        repository.setNameWithOwner("read-scope-org/widgets");
        repository.setHtmlUrl("https://github.com/read-scope-org/widgets");
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);

        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        repositoryToMonitorRepository.save(monitor);

        upsertPullRequest();
        upsertIssue();
        pullRequestId = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), PR_NUMBER)
                .orElseThrow()
                .getId();
        issueId = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), ISSUE_NUMBER)
                .orElseThrow()
                .getId();
    }

    @Test
    void aTombstonedPullRequestLeavesTheMentorsContextAndComesBackWhenUpstreamHandsItOver() {
        assertThat(mentorAuthoredPullRequestIds()).contains(pullRequestId);
        assertThat(pullRequestInventoryIds()).contains(pullRequestId);

        tombstonePullRequest();

        assertThat(mentorAuthoredPullRequestIds())
                .as("Heph would otherwise cite a pull request the developer cannot open")
                .doesNotContain(pullRequestId);
        assertThat(pullRequestInventoryIds()).doesNotContain(pullRequestId);

        upsertPullRequest();

        assertThat(mentorAuthoredPullRequestIds()).contains(pullRequestId);
        assertThat(pullRequestInventoryIds()).contains(pullRequestId);
    }

    @Test
    void aTombstonedIssueLeavesTheProjectInventoryAndComesBackWhenUpstreamHandsItOver() {
        assertThat(issueInventoryIds()).contains(issueId);

        tombstoneIssue();

        assertThat(issueInventoryIds()).doesNotContain(issueId);

        upsertIssue();

        assertThat(issueInventoryIds()).contains(issueId);
    }

    /**
     * The refusal is the reason itself, answered to the person who asked, rather than a status code
     * saying their button is broken: this workspace does monitor the pull request, so the question is
     * not one of standing.
     */
    @Test
    void askingForAReviewOfATombstonedPullRequestIsRefusedAsArtifactGone() {
        tombstonePullRequest();

        ManualReviewOutcome outcome =
                manualReviewRequests.requestPullRequestReview(workspace, gateLoadedPullRequest(), List.of(author));

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        assertThat(outcome.reason()).isEqualTo(SignalStateReason.ARTIFACT_GONE);

        upsertPullRequest();

        assertThat(manualReviewRequests
                        .requestPullRequestReview(workspace, gateLoadedPullRequest(), List.of(author))
                        .reason())
                .as("a resurrected pull request is back to whatever the gate makes of it")
                .isNotEqualTo(SignalStateReason.ARTIFACT_GONE);
    }

    @Test
    void askingForAReviewOfATombstonedIssueIsRefusedAsArtifactGone() {
        tombstoneIssue();

        ManualReviewOutcome outcome =
                manualReviewRequests.requestIssueReview(workspace, gateLoadedIssue(), List.of(author));

        assertThat(outcome.status()).isEqualTo(ManualReviewOutcome.Status.REFUSED);
        assertThat(outcome.reason()).isEqualTo(SignalStateReason.ARTIFACT_GONE);
    }

    /**
     * The loader must keep handing back a tombstoned row: telling "the row is gone" apart from "the row
     * is not visible right now" is what lets the resubmitters record terminal
     * {@code ARTIFACT_GONE} for the first and retryable {@code ARTIFACT_NOT_VISIBLE} for the second.
     */
    @Test
    void theGateLoaderAndTheUpsertLookupStillSeeATombstonedPullRequest() {
        tombstonePullRequest();

        assertThat(pullRequestRepository.findByIdWithAllForGate(pullRequestId))
                .get()
                .extracting(PullRequest::getId)
                .isEqualTo(pullRequestId);
        assertThat(pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), PR_NUMBER))
                .get()
                .extracting(PullRequest::getId)
                .isEqualTo(pullRequestId);
    }

    @Test
    void aTombstonedPullRequestHoldsItsPendingSignalUntilAnOrdinarySyncBringsItBack() {
        ArtifactSignal signal = recordSignal(pullRequestId, ScmSignals.PULL_REQUEST, ScmSignals.PULL_REQUEST_OPENED);
        tombstonePullRequest();

        resubmit(pullRequestResubmitter(), signal);

        ArtifactSignal held = reload(signal);
        assertThat(held.getState()).isEqualTo(SignalState.PENDING);
        assertThat(held.getStateReason()).isEqualTo(SignalStateReason.ARTIFACT_NOT_VISIBLE);
        assertThat(retryableSignalIds())
                .as("a held occasion the reaper never re-offers is retired in all but name")
                .contains(held.getId());

        upsertPullRequest();
        resubmit(pullRequestResubmitter(), signal);

        ArtifactSignal released = reload(signal);
        assertThat(released.getStateReason())
                .as("a resurrected pull request is back to whatever the gate makes of it")
                .isNotIn(SignalStateReason.ARTIFACT_NOT_VISIBLE, SignalStateReason.ARTIFACT_GONE);
        assertThat(released.getState()).isNotEqualTo(SignalState.PENDING);
    }

    @Test
    void aTombstonedIssueHoldsItsPendingSignalUntilAnOrdinarySyncBringsItBack() {
        ArtifactSignal signal = recordSignal(issueId, ScmSignals.ISSUE, ScmSignals.ISSUE_OPENED);
        tombstoneIssue();

        resubmit(issueResubmitter(), signal);

        ArtifactSignal held = reload(signal);
        assertThat(held.getState()).isEqualTo(SignalState.PENDING);
        assertThat(held.getStateReason()).isEqualTo(SignalStateReason.ARTIFACT_NOT_VISIBLE);
        assertThat(retryableSignalIds()).contains(held.getId());

        upsertIssue();
        resubmit(issueResubmitter(), signal);

        ArtifactSignal released = reload(signal);
        assertThat(released.getStateReason())
                .isNotIn(SignalStateReason.ARTIFACT_NOT_VISIBLE, SignalStateReason.ARTIFACT_GONE);
        assertThat(released.getState()).isNotEqualTo(SignalState.PENDING);
    }

    @Test
    void aLivePullRequestEventIsHeldWhileTheWorkIsTombstonedAndSubmittedOnceAnUpsertRestoresIt() {
        var data = ScmEventPayload.PullRequestData.from(gateLoadedPullRequest());
        var event = new ScmDomainEvent.PullRequestCreated(data, webhookContext());
        var jobs = mock(AgentJobService.class);
        var detectionGate = mock(PracticeReviewDetectionGate.class);
        var listener = new AgentJobEventListener(
                jobs, pullRequestRepository, detectionGate, workspaceResolver, signalRecorder);
        tombstonePullRequest();

        transactionTemplate.executeWithoutResult(status -> listener.onPullRequestCreated(event));

        ArtifactSignal held = artifactSignalRepository
                .findForArtifact(workspace.getId(), ScmSignals.PULL_REQUEST.value(), pullRequestId)
                .getFirst();
        assertHeld(held);
        verifyNoInteractions(jobs, detectionGate);

        upsertPullRequest();
        assertThat(gateLoadedPullRequest().getDeletedAt()).isNull();
        var decision = new GateDecision.Detect(workspace, List.of(), 1, TriggerMode.AUTO);
        when(detectionGate.evaluate(any(), eq(ScmSignals.PULL_REQUEST_OPENED), eq(TriggerMode.AUTO)))
                .thenReturn(decision);
        resubmit(
                new PullRequestSignalResubmitter(
                        jobs, pullRequestRepository, detectionGate, signalRecorder, reviewRepository),
                held);

        var request = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
        verify(jobs)
                .submit(
                        eq(workspace.getId()),
                        eq(AgentJobType.PULL_REQUEST_REVIEW),
                        request.capture(),
                        eq(held.key()),
                        eq(decision));
        PullRequest restored = gateLoadedPullRequest();
        assertThat(request.getValue().pullRequest().id()).isEqualTo(restored.getId());
        assertThat(request.getValue().pullRequest().title()).isEqualTo(restored.getTitle());
        assertThat(request.getValue().headRefOid()).isEqualTo(restored.getHeadRefOid());
        assertThat(request.getValue().headRefName()).isEqualTo(restored.getHeadRefName());
        assertThat(request.getValue().baseRefName()).isEqualTo(restored.getBaseRefName());
        assertThat(request.getValue().triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_OPENED);
    }

    @Test
    void aLiveIssueEventIsHeldWhileTheWorkIsTombstonedAndSubmittedOnceAnUpsertRestoresIt() {
        var data = transactionTemplate.execute(status -> ScmEventPayload.IssueData.from(gateLoadedIssue()));
        assertNotNull(data);
        var event = new ScmDomainEvent.IssueCreated(data, webhookContext());
        var jobs = mock(AgentJobService.class);
        var detectionGate = mock(PracticeReviewDetectionGate.class);
        var listener =
                new IssueAgentJobEventListener(jobs, issueRepository, detectionGate, workspaceResolver, signalRecorder);
        tombstoneIssue();

        transactionTemplate.executeWithoutResult(status -> listener.onIssueCreated(event));

        ArtifactSignal held = artifactSignalRepository
                .findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), issueId)
                .getFirst();
        assertHeld(held);
        verifyNoInteractions(jobs, detectionGate);

        upsertIssue();
        assertThat(gateLoadedIssue().getDeletedAt()).isNull();
        var decision = new GateDecision.Detect(workspace, List.of(), 1, TriggerMode.AUTO);
        when(detectionGate.evaluateIssue(any(), eq(ScmSignals.ISSUE_OPENED), eq(TriggerMode.AUTO)))
                .thenReturn(decision);
        resubmit(new IssueSignalResubmitter(jobs, issueRepository, detectionGate, signalRecorder), held);

        var request = ArgumentCaptor.forClass(IssueReviewSubmissionRequest.class);
        verify(jobs)
                .submit(
                        eq(workspace.getId()),
                        eq(AgentJobType.ISSUE_REVIEW),
                        request.capture(),
                        eq(held.key()),
                        eq(decision));
        Issue restored = gateLoadedIssue();
        assertThat(request.getValue().issueId()).isEqualTo(restored.getId());
        assertThat(request.getValue().issueNumber()).isEqualTo(restored.getNumber());
        assertThat(request.getValue().repositoryId()).isEqualTo(repository.getId());
        assertThat(request.getValue().title()).isEqualTo(restored.getTitle());
        assertThat(request.getValue().body()).isEqualTo(restored.getBody());
        assertThat(request.getValue().triggerSignal()).isEqualTo(ScmSignals.ISSUE_OPENED);
    }

    private void assertHeld(ArtifactSignal held) {
        assertThat(held.getState()).isEqualTo(SignalState.PENDING);
        assertThat(held.getStateReason()).isEqualTo(SignalStateReason.ARTIFACT_NOT_VISIBLE);
        assertThat(held.getDiscoveredVia()).isEqualTo(DiscoveredVia.EVENT);
        assertThat(retryableSignalIds()).contains(held.getId());
    }

    private EventContext webhookContext() {
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                new RepositoryRef(repository.getId(), repository.getNameWithOwner(), "main"),
                DataSource.WEBHOOK,
                "opened",
                UUID.randomUUID().toString(),
                null);
    }

    /**
     * Both resubmitters are {@code @ConditionalOnProperty(hephaestus.agent.enabled)}, which the test
     * profile leaves off, so they are built from the same beans the container would inject.
     */
    private PullRequestSignalResubmitter pullRequestResubmitter() {
        return new PullRequestSignalResubmitter(
                agentJobService, pullRequestRepository, gate, signalRecorder, reviewRepository);
    }

    private IssueSignalResubmitter issueResubmitter() {
        return new IssueSignalResubmitter(agentJobService, issueRepository, gate, signalRecorder);
    }

    /** Supplies the transaction the bean's own {@code REQUIRES_NEW} would open around the ledger writes. */
    private void resubmit(PendingSignalResubmitter resubmitter, ArtifactSignal signal) {
        transactionTemplate.executeWithoutResult(status -> resubmitter.resubmit(signal));
    }

    private ArtifactSignal recordSignal(long artifactId, ArtifactKind kind, SignalName signalName) {
        SignalKey key = new SignalKey(workspace.getId(), artifactId, signalName, REVISION);
        transactionTemplate.executeWithoutResult(
                status -> signalRecorder.record(key, Instant.now(), DiscoveredVia.EVENT));
        return artifactSignalRepository
                .findForArtifact(workspace.getId(), kind.value(), artifactId)
                .getFirst();
    }

    private ArtifactSignal reload(ArtifactSignal signal) {
        return artifactSignalRepository.findById(signal.getId()).orElseThrow();
    }

    /** What the reaper's next sweep would pick up, with every retry interval already elapsed. */
    private List<UUID> retryableSignalIds() {
        return artifactSignalRepository
                .findRetryablePending(Instant.now().plus(Duration.ofDays(1)), FIRST_PAGE)
                .stream()
                .map(ArtifactSignal::getId)
                .toList();
    }

    private void tombstonePullRequest() {
        assertThat(issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(
                        repository.getId(), List.of(PR_NUMBER), Instant.now()))
                .isEqualTo(1);
    }

    private void tombstoneIssue() {
        assertThat(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(
                        repository.getId(), List.of(ISSUE_NUMBER), Instant.now()))
                .isEqualTo(1);
    }

    private List<Long> mentorAuthoredPullRequestIds() {
        return mentorContextQueryRepository
                .findRecentAuthoredPullRequests(workspace.getId(), author.getId(), FIRST_PAGE)
                .stream()
                .map(PullRequest::getId)
                .toList();
    }

    private List<Long> pullRequestInventoryIds() {
        return pullRequestRepository.findPullRequestInventoryByRepositoryId(repository.getId(), FIRST_PAGE).stream()
                .map(PullRequest::getId)
                .toList();
    }

    private List<Long> issueInventoryIds() {
        return issueRepository.findIssueInventoryByRepositoryId(repository.getId(), FIRST_PAGE).stream()
                .map(Issue::getId)
                .toList();
    }

    /** The association graph a request needs: standing is judged on the author and the assignees. */
    private PullRequest gateLoadedPullRequest() {
        return pullRequestRepository.findByIdWithAllForGate(pullRequestId).orElseThrow();
    }

    private Issue gateLoadedIssue() {
        return issueRepository.findByIdWithRepositoryAndAssignees(issueId).orElseThrow();
    }

    private void upsertPullRequest() {
        Instant now = Instant.now();
        pullRequestRepository.upsertCore(
                9400L + PR_NUMBER,
                providerId(),
                PR_NUMBER,
                "A change worth reviewing",
                "Body",
                "OPEN",
                null,
                "https://github.com/" + repository.getNameWithOwner() + "/pull/" + PR_NUMBER,
                false,
                null,
                0,
                now,
                now,
                now,
                author.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/branch",
                "main",
                "headsha",
                "basesha",
                null,
                null);
    }

    private void upsertIssue() {
        Instant now = Instant.now();
        issueRepository.upsertCore(
                9500L + ISSUE_NUMBER,
                providerId(),
                ISSUE_NUMBER,
                "Something worth discussing",
                "Body",
                "OPEN",
                null,
                "https://github.com/" + repository.getNameWithOwner() + "/issues/" + ISSUE_NUMBER,
                false,
                null,
                0,
                now,
                now,
                now,
                author.getId(),
                repository.getId(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private Long providerId() {
        Long providerId = repository.getProvider().getId();
        assertNotNull(providerId);
        return providerId;
    }
}
