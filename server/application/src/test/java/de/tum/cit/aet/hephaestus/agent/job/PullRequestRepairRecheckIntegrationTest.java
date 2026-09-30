package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWorkFixtures;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalReaper;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalLedgerProperties;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A merge request whose author answered a problem — by editing the description or pushing — is reviewed again
 * for that problem's practice once the burst settles, through the real ledger, gate and admission.
 */
class PullRequestRepairRecheckIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String REPO = "org/repair-repo";
    private static final String HEAD_1 = "1".repeat(40);
    private static final String HEAD_2 = "2".repeat(40);
    private static final String EXPLAINED = "Adds the thing because reviewers could not tell why";

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IntegrationManifestRegistry manifests;

    @Autowired
    private WorkspaceLlmConnectionRepository connectionRepository;

    @Autowired
    private WorkspaceLlmModelRepository modelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository bindingRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private SignalRecorder recorder;

    @Autowired
    private PracticeReviewDetectionGate gate;

    @Autowired
    private AgentJobService agentJobService;

    @Autowired
    private WorkspaceResolver workspaceResolver;

    @Autowired
    private PracticeReviewProperties reviewProperties;

    @Autowired
    private ObservationInvalidationRepository invalidationRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SignalLedgerProperties ledgerProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private Workspace workspace;
    private User developer;
    private Repository repository;
    private AgentJobEventListener listener;
    private PullRequestPushCoalescer coalescer;
    private PullRequestSignalResubmitter resubmitter;

    @BeforeEach
    void setUp() {
        workspace = WorkspaceTestFixtures.activeWorkspace("repair-recheck");
        workspace.setAccountLogin("org");
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);
        bindModel(workspace);
        developer = persistUser("repairing-developer");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

        repository = new Repository();
        repository.setNativeId(4001L);
        repository.setProvider(ensureGitHubProvider());
        repository.setName("repair-repo");
        repository.setNameWithOwner(REPO);
        repository.setHtmlUrl("https://github.com/" + REPO);
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);

        listener = new AgentJobEventListener(
                agentJobService, pullRequestRepository, gate, workspaceResolver, recorder, manifests);
        resubmitter = new PullRequestSignalResubmitter(
                agentJobService, pullRequestRepository, gate, recorder, reviewRepository, manifests);
        coalescer = new PullRequestPushCoalescer(
                signals,
                pullRequestRepository,
                recorder,
                resubmitter,
                workspaceResolver,
                reviewProperties,
                transactions);
    }

    @Test
    void shouldRecheckOnlyTheOpenProblemOnceWhenTheDescriptionIsRepairedAtTheSameHead() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        Practice sized = practice("scope-one-reviewable-change", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        observe(sized, opened, pr.getId(), developer, ObservationKind.DEMONSTRATED_STRENGTH, null, NOW);

        edit("Adds the thing. Why: first try", Set.of("body"));
        edit("Adds the thing. Why: first try", Set.of("body")); // the same delivery again
        edit("Adds the thing. Why: first try", Set.of("relationships")); // a label moved
        edit("Adds the thing because reviewers could not tell why", Set.of("body"));

        List<ArtifactSignal> edits = signalsOf(pr, ScmSignals.PULL_REQUEST_EDITED);
        assertThat(edits)
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getState()).isEqualTo(SignalState.DEFERRED));

        settle(pr);

        SignalKey repaired = currentKey(pr, ScmSignals.PULL_REQUEST_EDITED);
        ArtifactSignal reviewed = signalsOf(pr, ScmSignals.PULL_REQUEST_EDITED).stream()
                .filter(row -> row.key().equals(repaired))
                .findFirst()
                .orElseThrow();
        assertThat(signalsOf(pr, ScmSignals.PULL_REQUEST_EDITED)).allSatisfy(row -> {
            if (row.getId().equals(reviewed.getId())) {
                assertThat(row.getState()).isEqualTo(SignalState.TRIGGERED);
            } else {
                assertThat(row.getStateReason()).isEqualTo(SignalStateReason.COALESCED);
            }
        });
        AgentJob recheck = jobOf(reviewed);
        assertThat(signalOf(recheck)).isEqualTo(ScmSignals.PULL_REQUEST_EDITED.value());
        assertThat(recheckedOf(recheck)).isEqualTo("[\"describe-what-and-why\"]");
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(opened.getId(), recheck.getId());
    }

    @Test
    void shouldReviewAPushTogetherWithAnEditAsOnePushReviewThatAlsoRechecks() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        practice("ships-tests-with-the-change", ScmSignals.PULL_REQUEST_SYNCHRONIZED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);

        push(HEAD_2);
        edit("Adds the thing because reviewers could not tell why", Set.of("body"));
        settle(pr);

        ArtifactSignal pushed =
                signalsOf(pr, ScmSignals.PULL_REQUEST_SYNCHRONIZED).getFirst();
        assertThat(pushed.getState()).isEqualTo(SignalState.TRIGGERED);
        assertThat(signalsOf(pr, ScmSignals.PULL_REQUEST_EDITED))
                .singleElement()
                .satisfies(row -> assertThat(row.getStateReason()).isEqualTo(SignalStateReason.COALESCED));
        AgentJob review = jobOf(pushed);
        assertThat(signalOf(review)).isEqualTo(ScmSignals.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(recheckedOf(review)).isEqualTo("[\"describe-what-and-why\"]");
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(opened.getId(), review.getId());
    }

    @Test
    void shouldReviewADescriptionWrittenAgainAfterAPushChangedTheWork() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        String explained = "Adds the thing because reviewers could not tell why";
        edit(explained, Set.of("body"));
        settle(pr);
        push(HEAD_2);
        settle(pr);
        edit("Adds the thing", Set.of("body"));
        settle(pr);

        edit(explained, Set.of("body"));

        ArtifactSignal again = rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED));
        assertThat(again.getState()).isEqualTo(SignalState.DEFERRED);
        settle(pr);
        AgentJob recheck = jobOf(rowOf(again.key()));
        assertThat(signalOf(recheck)).isEqualTo(ScmSignals.PULL_REQUEST_EDITED.value());
        assertThat(recheckedOf(recheck)).isEqualTo("[\"describe-what-and-why\"]");
    }

    /**
     * An edit whose mirror write holds the pull request's lock while the coalescer drains an older push: the drain
     * waits for it, finds the edit's occasion in the group, and leaves the whole group to the edit's own deadline.
     */
    @Test
    void shouldHoldAnOlderPushForAnEditThatCommitsWhileTheGroupIsDrained() throws Exception {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        SignalKey olderPush = currentKey(pr, ScmSignals.PULL_REQUEST_SYNCHRONIZED);
        Instant quietSince =
                Instant.now().minus(PullRequestPushCoalescer.QUIET_PERIOD).minusSeconds(60);
        transactions.executeWithoutResult(
                status -> signals.insertDeferred(olderPush, UUID.randomUUID(), quietSince, quietSince, null));
        CountDownLatch editWritten = new CountDownLatch(1);
        CountDownLatch releaseEdit = new CountDownLatch(1);
        boolean drainWaited;
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> editing = threads.submit(() -> transactions.executeWithoutResult(status -> {
                pullRequestRepository
                        .findForUpdateByRepositoryIdAndNumber(repository.getId(), pr.getNumber())
                        .orElseThrow();
                upsert(false, HEAD_1, "Adds the thing because reviewers could not tell why");
                listener.onPullRequestUpdated(new ScmDomainEvent.PullRequestUpdated(
                        ScmEventPayload.PullRequestData.from(reload()), Set.of("body"), liveContext()));
                editWritten.countDown();
                awaitUninterruptibly(releaseEdit);
            }));
            assertThat(editWritten.await(30, TimeUnit.SECONDS)).isTrue();

            Future<?> draining = threads.submit(() -> transactions.executeWithoutResult(
                    status -> coalescer.drain(workspace.getId(), pr.getId(), Instant.now())));
            drainWaited = aBackendWaitsOnALock();
            releaseEdit.countDown();
            editing.get(30, TimeUnit.SECONDS);
            draining.get(30, TimeUnit.SECONDS);
        } finally {
            releaseEdit.countDown();
            threads.shutdownNow();
        }

        SignalKey edit = currentKey(pr, ScmSignals.PULL_REQUEST_EDITED);
        assertThat(rowOf(olderPush).getState()).isEqualTo(SignalState.DEFERRED);
        assertThat(rowOf(edit).getState()).isEqualTo(SignalState.DEFERRED);
        assertThat(jobsOf(workspace)).containsExactly(opened.getId());
        assertThat(drainWaited).isTrue();

        settle(pr);

        AgentJob review = jobOf(rowOf(olderPush));
        assertThat(signalOf(review)).isEqualTo(ScmSignals.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(recheckedOf(review)).isEqualTo("[\"describe-what-and-why\"]");
        assertThat(rowOf(edit).getStateReason()).isEqualTo(SignalStateReason.COALESCED);
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(opened.getId(), review.getId());
    }

    @Test
    void shouldRefuseAReofferedOccasionWhoseRepositoryAnotherWorkspaceNowMonitors() {
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        SignalKey queued = pending(pr, ScmSignals.PULL_REQUEST_OPENED);
        workspace.setAccountLogin("elsewhere");
        workspace = workspaceRepository.save(workspace);
        Workspace monitoring = WorkspaceTestFixtures.activeWorkspace("repair-recheck-moved");
        monitoring.setAccountLogin("org");
        monitoring.getFeatures().setPracticesEnabled(true);
        monitoring = workspaceRepository.save(monitoring);
        bindModel(monitoring);
        ensureWorkspaceMembership(monitoring, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        practice(monitoring, "describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);

        // An opened occasion goes to the submitter, constructed here without the transaction its bean would open.
        transactions.executeWithoutResult(status -> reaper().sweep());

        assertThat(rowOf(queued).getStateReason()).isEqualTo(SignalStateReason.OUT_OF_REVIEW_SCOPE);
        assertThat(jobsOf(monitoring)).isEmpty();
        assertThat(jobsOf(workspace)).isEmpty();
    }

    /** The reaper re-offers a push held back for budget while a newer edit still waits out its quiet period. */
    @Test
    void shouldHoldAReofferedPushForANewerEditAndThenReviewThemOnce() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        practice("ships-tests-with-the-change", ScmSignals.PULL_REQUEST_SYNCHRONIZED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        SignalKey heldPush = pending(pr, ScmSignals.PULL_REQUEST_SYNCHRONIZED);
        edit("Adds the thing because reviewers could not tell why", Set.of("body"));
        SignalKey newerEdit = currentKey(pr, ScmSignals.PULL_REQUEST_EDITED);

        reoffer();

        assertThat(rowOf(heldPush).getState()).isEqualTo(SignalState.PENDING);
        assertThat(rowOf(newerEdit).getState()).isEqualTo(SignalState.DEFERRED);
        assertThat(jobsOf(workspace)).containsExactly(opened.getId());

        settle(pr);

        AgentJob review = jobOf(rowOf(heldPush));
        assertThat(signalOf(review)).isEqualTo(ScmSignals.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(recheckedOf(review)).isEqualTo("[\"describe-what-and-why\"]");
        assertThat(rowOf(newerEdit).getStateReason()).isEqualTo(SignalStateReason.COALESCED);
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(opened.getId(), review.getId());
    }

    /** A merge holding the pull request's lock while the reaper re-offers an edit held back before it. */
    @Test
    void shouldSettleAReofferedEditBehindAMergeThatCommitsWhileItWaits() throws Exception {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing because reviewers could not tell why");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        SignalKey queued = pending(pr, ScmSignals.PULL_REQUEST_EDITED);
        CountDownLatch mergeWritten = new CountDownLatch(1);
        CountDownLatch releaseMerge = new CountDownLatch(1);
        boolean reofferWaited;
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> merging = threads.submit(() -> transactions.executeWithoutResult(status -> {
                PullRequest locked = pullRequestRepository
                        .findForUpdateByRepositoryIdAndNumber(repository.getId(), pr.getNumber())
                        .orElseThrow();
                locked.setState(Issue.State.MERGED);
                locked.setMerged(true);
                pullRequestRepository.saveAndFlush(locked);
                mergeWritten.countDown();
                awaitUninterruptibly(releaseMerge);
            }));
            assertThat(mergeWritten.await(30, TimeUnit.SECONDS)).isTrue();

            Future<?> reoffering = threads.submit(this::reoffer);
            reofferWaited = aBackendWaitsOnALock();
            releaseMerge.countDown();
            merging.get(30, TimeUnit.SECONDS);
            reoffering.get(30, TimeUnit.SECONDS);
        } finally {
            releaseMerge.countDown();
            threads.shutdownNow();
        }

        assertThat(rowOf(queued).getStateReason()).isEqualTo(SignalStateReason.COALESCED);
        assertThat(jobsOf(workspace)).containsExactly(opened.getId());
        assertThat(reofferWaited).isTrue();
    }

    @Test
    void shouldRecheckOnlyAProblemThatIsStillTheCurrentWordOnTheWork() {
        Practice answered = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        Practice withdrawn = practice("links-the-change-to-its-issue", ScmSignals.PULL_REQUEST_OPENED);
        Practice unanswered = practice("states-how-to-verify-the-change", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(answered, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        UUID invalid =
                observe(withdrawn, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        observe(unanswered, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        AgentJob ready = capturedReview(NOW.plusSeconds(60));
        observe(
                answered,
                ready,
                pr.getId(),
                developer,
                ObservationKind.DEMONSTRATED_STRENGTH,
                null,
                NOW.plusSeconds(60));
        invalidationRepository.save(new ObservationInvalidation(
                observationRepository.findById(invalid).orElseThrow(), 1L, "Wrong when made", NOW.plusSeconds(90)));
        upsert(false, HEAD_1, EXPLAINED);
        pr = reload();

        assertThat(rechecked(revision(pr))).containsExactly("states-how-to-verify-the-change");
        assertThat(gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                .isInstanceOfSatisfying(
                        GateDecision.Detect.class,
                        detect -> assertThat(detect.recheckedPractices()).isEmpty());
    }

    @Test
    void shouldStillRecheckAProblemThatALaterRunRecordedNothingForButNotOnDraftOrMergedWork() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        Practice sized = practice("scope-one-reviewable-change", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        AgentJob recheck = capturedReview(NOW.plusSeconds(60));
        observe(
                sized,
                recheck,
                pr.getId(),
                developer,
                ObservationKind.DEMONSTRATED_STRENGTH,
                null,
                NOW.plusSeconds(60));
        upsert(false, HEAD_1, EXPLAINED);
        pr = reload();

        assertThat(rechecked(revision(pr))).containsExactly("describe-what-and-why");
        pr.setDraft(true);
        assertThat(revision(pr)).isInstanceOf(GateDecision.Skip.class);
        pr.setDraft(false);
        pr.setState(Issue.State.MERGED);
        assertThat(revision(pr)).isInstanceOf(GateDecision.Skip.class);
    }

    /**
     * An edit that waited out its quiet period while a review read the edited work: that review's problem was
     * recorded on this very work, so the edit rechecks nothing, while a practice the edit is an occasion for runs.
     */
    @Test
    void shouldRecheckNothingWhenADeferredEditSettlesAfterAReviewAlreadyReadThatWork() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        practice("states-how-to-verify-the-change", ScmSignals.PULL_REQUEST_EDITED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        edit(EXPLAINED, Set.of("body"));
        AgentJob ready = capturedReview(NOW);
        observe(describe, ready, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);

        settle(pr);

        AgentJob review = jobOf(rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED)));
        assertThat(signalOf(review)).isEqualTo(ScmSignals.PULL_REQUEST_EDITED.value());
        assertThat(Objects.requireNonNull(review.getMetadata()).has(AgentJob.RECHECKED_PRACTICES_METADATA_KEY))
                .isFalse();
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(ready.getId(), review.getId());
    }

    @Test
    void shouldAdmitNoRepairJobWhenTheOnlyProblemWasRecordedOnTheWorkAsItStands() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        edit(EXPLAINED, Set.of("body"));
        AgentJob ready = capturedReview(NOW);
        observe(describe, ready, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);

        settle(pr);

        assertThat(rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED)).getJobId())
                .isNull();
        assertThat(jobsOf(workspace)).containsExactly(ready.getId());
    }

    @Test
    void shouldRecheckAProblemWhenOnlyTheHeadMovedSinceItWasRecorded() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);

        push(HEAD_2);
        settle(pr);

        AgentJob review =
                jobOf(signalsOf(pr, ScmSignals.PULL_REQUEST_SYNCHRONIZED).getFirst());
        assertThat(recheckedOf(review)).isEqualTo("[\"describe-what-and-why\"]");
    }

    @Test
    void shouldRecheckOnlyTheProblemsRecordedOnWorkThatHasSinceChanged() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        Practice verify = practice("states-how-to-verify-the-change", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        upsert(false, HEAD_1, EXPLAINED);
        AgentJob ready = capturedReview(NOW.plusSeconds(60));
        observe(
                verify,
                ready,
                pr.getId(),
                developer,
                ObservationKind.OMISSION_GAP,
                Severity.MINOR,
                NOW.plusSeconds(60));

        assertThat(rechecked(revision(reload()))).containsExactly("describe-what-and-why");
    }

    /** A held-back occasion re-offered once the work has moved past what the problem's review read. */
    @Test
    void shouldRecheckFromAReofferedOccasionWhenTheWorkHasSinceChanged() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob opened = capturedReview(NOW);
        observe(describe, opened, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        upsert(false, HEAD_1, EXPLAINED);
        SignalKey held = pending(pr, ScmSignals.PULL_REQUEST_EDITED);

        reoffer();

        AgentJob review = jobOf(rowOf(held));
        assertThat(recheckedOf(review)).isEqualTo("[\"describe-what-and-why\"]");
    }

    @Test
    void shouldRecheckNoProblemWhoseReviewCaptureCannotBeCompared() {
        Practice absent = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        Practice malformed = practice("states-how-to-verify-the-change", ScmSignals.PULL_REQUEST_OPENED);
        Practice foreign = practice("links-the-change-to-its-issue", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD_1, "Adds the thing");
        AgentJob uncaptured = reviewWith(NOW, null);
        observe(absent, uncaptured, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        // The manifest alone would show the description changed; a malformed identity must not fall back to it.
        ObjectNode broken = snapshot(ReviewedWorkFixtures.pullRequestManifest(NOW, "Adds the thing", HEAD_1), null);
        broken.putObject(ReviewedWork.SNAPSHOT_KEY)
                .put("artifactKind", ArtifactKinds.PULL_REQUEST.value())
                .put("artifactId", pr.getId())
                .put("titleAndDescriptionRevision", "not-a-digest")
                .put("head", "not-a-commit")
                .put("capturedAt", NOW.toString());
        AgentJob garbled = reviewWith(NOW, broken);
        observe(malformed, garbled, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("repair-recheck-other"));
        AgentJob elsewhere = capturedReview(NOW);
        elsewhere.setWorkspace(other);
        elsewhere = agentJobRepository.save(elsewhere);
        observe(foreign, elsewhere, pr.getId(), developer, ObservationKind.OMISSION_GAP, Severity.MINOR, NOW);
        upsert(false, HEAD_1, EXPLAINED);

        // Nothing is rechecked, and nothing is bound to the edit itself, so no review starts at all.
        assertThat(revision(reload()))
                .isInstanceOfSatisfying(
                        GateDecision.Skip.class,
                        skip -> assertThat(skip.reason()).isEqualTo("no matching practices"));
    }

    private void bindModel(Workspace workspace) {
        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setWorkspace(workspace);
        connection.setSlug("repair-connection");
        connection.setDisplayName("Repair connection");
        connection.setBaseUrl("https://api.openai.com");
        connection.setApiProtocol("openai-completions");
        connection.setEnabled(true);
        connection = connectionRepository.save(connection);
        WorkspaceLlmModel model = new WorkspaceLlmModel();
        model.setWorkspace(workspace);
        model.setConnection(connection);
        model.setSlug("repair-model");
        model.setDisplayName("Repair model");
        model.setUpstreamModelId("gpt-5");
        model.setEnabled(true);
        model = modelRepository.save(model);
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setWorkspaceModel(model);
        binding.setTimeoutSeconds(300);
        bindingRepository.save(binding);
    }

    /** A review of the pull request as it stands now, recording the title, description and head it read. */
    private AgentJob capturedReview(Instant completedAt) {
        PullRequest read = reload();
        return reviewWith(
                completedAt,
                snapshot(
                        ReviewedWorkFixtures.pullRequestManifest(completedAt, read.getBody(), read.getHeadRefOid()),
                        new ReviewedWork(
                                ArtifactKinds.PULL_REQUEST.value(),
                                read.getId(),
                                ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, read.getTitle(), read.getBody()),
                                read.getHeadRefOid(),
                                completedAt)));
    }

    /** A review whose snapshot is {@code snapshot}, or the seed's own when null: a contract and no capture. */
    private AgentJob reviewWith(Instant completedAt, @Nullable ObjectNode snapshot) {
        PullRequest read = reload();
        AgentJob job = persistPullRequestReview(workspace, read.getNumber(), read.getId(), completedAt);
        if (snapshot == null) {
            return job;
        }
        job.setEvidenceSnapshot(snapshot);
        return agentJobRepository.save(job);
    }

    private ObjectNode snapshot(ArtifactSourceManifest manifest, @Nullable ReviewedWork work) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("manifest", objectMapper.valueToTree(manifest));
        if (work != null) {
            snapshot.set(ReviewedWork.SNAPSHOT_KEY, objectMapper.valueToTree(work));
        }
        return snapshot;
    }

    private Practice practice(String slug, SignalName occasion) {
        return practice(workspace, slug, occasion);
    }

    private Practice practice(Workspace workspace, String slug, SignalName occasion) {
        Practice practice = persistPractice(workspace, null, slug, slug, null);
        practice.setBindings(PracticeTestEvidence.bindings(occasion));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.saveAndFlush(practice);
    }

    private PullRequest pullRequest(boolean draft, String head, String body) {
        upsert(draft, head, body);
        return reload();
    }

    private void upsert(boolean draft, String head, String body) {
        Instant now = Instant.now();
        pullRequestRepository.upsertCore(
                7101L,
                Objects.requireNonNull(repository.getProvider().getId()),
                2,
                "Adds the thing",
                body,
                "OPEN",
                null,
                "https://github.com/" + REPO + "/pull/2",
                false,
                null,
                0,
                now,
                now,
                now,
                developer.getId(),
                repository.getId(),
                null,
                null,
                draft,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/repair",
                "main",
                head,
                "base-sha",
                null,
                null);
    }

    private PullRequest reload() {
        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), 2)
                .orElseThrow();
        return pullRequestRepository.findByIdWithAllForGate(pr.getId()).orElseThrow();
    }

    private void edit(String body, Set<String> changedFields) {
        upsert(false, Objects.requireNonNull(reload().getHeadRefOid()), body);
        var event = new ScmDomainEvent.PullRequestUpdated(
                ScmEventPayload.PullRequestData.from(reload()), changedFields, liveContext());
        transactions.executeWithoutResult(status -> listener.onPullRequestUpdated(event));
    }

    private void push(String head) {
        upsert(false, head, Objects.requireNonNull(reload().getBody()));
        var event = new ScmDomainEvent.PullRequestSynchronized(
                ScmEventPayload.PullRequestData.from(reload()), liveContext());
        transactions.executeWithoutResult(status -> listener.onPullRequestSynchronized(event));
    }

    private EventContext liveContext() {
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                null,
                DataSource.WEBHOOK,
                "update",
                UUID.randomUUID().toString(),
                null);
    }

    /** Past the quiet period, as the sweep would find the burst. */
    private void settle(PullRequest pr) {
        Instant settled =
                Instant.now().plus(PullRequestPushCoalescer.QUIET_PERIOD).plus(Duration.ofMinutes(1));
        transactions.executeWithoutResult(status -> coalescer.drain(workspace.getId(), pr.getId(), settled));
    }

    private List<ArtifactSignal> signalsOf(PullRequest pr, SignalName signal) {
        return signals.findForArtifact(workspace.getId(), ScmSignals.PULL_REQUEST.value(), pr.getId()).stream()
                .filter(row -> signal.value().equals(row.getSignalName()))
                .toList();
    }

    private SignalKey currentKey(PullRequest pr, SignalName signal) {
        PullRequest current = reload();
        return ScmSignals.pullRequestKey(
                        workspace.getId(),
                        pr.getId(),
                        signal,
                        current.getHeadRefOid(),
                        current.getTitle(),
                        current.getBody())
                .orElseThrow();
    }

    /** An occasion admission held back for want of budget, due for the reaper's next re-offer. */
    private SignalKey pending(PullRequest pr, SignalName signal) {
        SignalKey key = currentKey(pr, signal);
        transactions.executeWithoutResult(status -> {
            recorder.record(key, Instant.now(), DiscoveredVia.EVENT);
            recorder.markRefused(key, SignalStateReason.BUDGET_EXHAUSTED);
        });
        jdbcTemplate.update(
                "UPDATE artifact_signal SET state_changed_at = state_changed_at - INTERVAL '2 hours' WHERE id = ?",
                rowOf(key).getId());
        return key;
    }

    /** As the scheduler runs it: the claim commits before the coalescer opens its own transaction. */
    private void reoffer() {
        reaper().sweep();
    }

    private PendingSignalReaper reaper() {
        return new PendingSignalReaper(signals, ledgerProperties, List.of(coalescer));
    }

    private ArtifactSignal rowOf(SignalKey key) {
        return signals.findForArtifact(workspace.getId(), ScmSignals.PULL_REQUEST.value(), key.artifactId()).stream()
                .filter(row -> row.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    /** Whether another backend waits on a lock within ten seconds. */
    private boolean aBackendWaitsOnALock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (!jdbcTemplate.queryForList("""
                            SELECT pid FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                            """, Integer.class).isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private AgentJob jobOf(ArtifactSignal signal) {
        UUID id = Objects.requireNonNull(signal.getJobId());
        return agentJobRepository.findByIdAndWorkspaceId(id, workspace.getId()).orElseThrow();
    }

    private List<UUID> jobsOf(Workspace workspace) {
        return agentJobRepository.findListRows(workspace.getId(), null, Pageable.unpaged()).stream()
                .map(row -> row.getId())
                .toList();
    }

    private static String signalOf(AgentJob job) {
        return Objects.requireNonNull(job.getMetadata()).path("signal").asString();
    }

    private static String recheckedOf(AgentJob job) {
        return Objects.requireNonNull(job.getMetadata())
                .path(AgentJob.RECHECKED_PRACTICES_METADATA_KEY)
                .toString();
    }

    private GateDecision revision(PullRequest pr) {
        return gate.evaluateQueued(pr, workspace.getId(), ScmSignals.PULL_REQUEST_EDITED, pr.reviewSubject(), true);
    }

    private static Set<String> rechecked(GateDecision decision) {
        assertThat(decision).isInstanceOf(GateDecision.Detect.class);
        return ((GateDecision.Detect) decision).recheckedPractices();
    }
}
