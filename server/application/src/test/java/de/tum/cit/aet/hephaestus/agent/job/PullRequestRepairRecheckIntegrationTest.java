package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWorkFixtures;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalReaper;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalLedgerProperties;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
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
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A merge request whose author answered a problem — by editing the description or pushing — is reviewed again
 * for that problem's practice once the burst settles, through the real ledger, gate and admission.
 */
class PullRequestRepairRecheckIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String REPO = "org/repair-repo";
    private static final String HEAD = "1".repeat(40);
    private static final String NEXT_HEAD = "2".repeat(40);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private LlmModelResolver modelResolver;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ReviewedWorkChanges reviewedWorkChanges;

    @Autowired
    private WorkspaceContextBuilder folderBuilder;

    @Autowired
    private JobEvidenceFiles evidenceFiles;

    @Autowired
    private ObservationAdmissionService admissionService;

    @Autowired
    private JobTypeHandlerRegistry handlerRegistry;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private SignalRecorder recorder;

    @Autowired
    private ReviewGate gate;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecheckOnlyTheOpenProblemOnceWhenTheDescriptionIsRepairedAtTheSameHead(boolean gitLab) {
        provider(gitLab);
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        Practice sized = practice("scope-one-reviewable-change", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        observe(sized, opened, pr.getId(), developer, Outcome.MET, null, NOW);

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecheckTheMergedRequestsCurrentNegativeAfterTheLinkedClosedIssueChanges(boolean gitLab)
            throws Exception {
        provider(gitLab);
        Connection scm = new Connection(
                workspace,
                gitLab ? IntegrationKind.GITLAB : IntegrationKind.GITHUB,
                "repair-scm",
                gitLab
                        ? new ConnectionConfig.GitLabConfig(
                                "https://gitlab.com",
                                null,
                                null,
                                ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                                Set.of(),
                                null)
                        : new ConnectionConfig.GitHubAppConfig(1732L, "org", null, Set.of()));
        scm.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(scm);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, REPO));
        Practice linked = practice("closing-issue-criteria", ScmSignals.PULL_REQUEST_MERGED);
        linked.setSignals(List.of(ScmSignals.PULL_REQUEST_MERGED));
        linked.setEvidenceRequirements(List.of(
                new PracticeEvidenceRequirement(new SourceKind("scm.linked-work-items"), EvidenceStance.REQUIRED)));
        linked.setReviewWhen(Map.of());
        linked.setSubject(ActorRole.AUTHOR);
        linked.setPrecondition(null);
        linked.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(linked, 2)));
        linked = practiceRepository.saveAndFlush(linked);
        PullRequest pr = pullRequest(false, HEAD, "Closes #18");
        Issue issue = new Issue();
        issue.setNativeId(7201L);
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber(18);
        issue.setState(Issue.State.CLOSED);
        issue.setTitle("Acceptance criteria");
        issue.setBody("- [ ] Confirm repair");
        issue.setCreatedAt(NOW.minus(Duration.ofDays(2)));
        issue.setClosedAt(NOW);
        issue = issueRepository.saveAndFlush(issue);
        pr.setState(Issue.State.MERGED);
        pr.setMerged(true);
        pr.setMergedAt(NOW);
        long linkedIssueId = issue.getId();
        transactions.executeWithoutResult(status -> {
            PullRequest managed = pullRequestRepository.findById(pr.getId()).orElseThrow();
            managed.setState(Issue.State.MERGED);
            managed.setMerged(true);
            managed.setMergedAt(NOW);
            managed.replaceClosingIssues(Set.of(issueRepository.getReferenceById(linkedIssueId)));
            pullRequestRepository.saveAndFlush(managed);
        });
        AgentJob merged = admittedLinkedReview(pr, linked);
        UUID olderNegative = observationRepository
                .findByAgentJobId(merged.getId(), workspace.getId())
                .getFirst()
                .getId();
        Feedback oldBrief = persistFeedback(
                merged, developer, FeedbackChannel.IN_CHAT, 0, FeedbackDeliveryState.PREPARED, "Old guidance", NOW);
        bind(oldBrief, olderNegative);
        Feedback deliveredCard =
                persistInAppFeedback(merged, developer, 1, FeedbackDeliveryState.DELIVERED, "Recorded history", NOW);
        bind(deliveredCard, olderNegative);
        AgentJob laterNegativeRun = admittedLinkedReview(pr, linked);
        laterNegativeRun.setRetryCount(1);
        laterNegativeRun = agentJobRepository.saveAndFlush(laterNegativeRun);
        List<Observation> admitted =
                observationRepository.findByAgentJobId(laterNegativeRun.getId(), workspace.getId());
        UUID currentNegative = admitted.getFirst().getId();
        for (AgentJob baseline : List.of(merged, laterNegativeRun)) {
            JsonNode manifest = agentJobRepository
                    .findById(baseline.getId())
                    .orElseThrow()
                    .getEvidenceSnapshot()
                    .path("manifest");
            assertThat(manifest.path("artifacts")).isEmpty();
            for (JsonNode source : manifest.path("sources"))
                assertThat(source.path("artifacts")).isEmpty();
        }
        UUID producingRunId = laterNegativeRun.getId();
        assertThat(admitted)
                .allSatisfy(row -> assertThat(de.tum.cit.aet.hephaestus.agent.handler.CitationVerification.isVerified(
                                producingRunId,
                                0,
                                Objects.requireNonNull(row.getEvidence()).path("citations")))
                        .isTrue());
        AgentJob gateCapture;
        try (LinkedAttempt capture = captureLinkedAttempt(linkedReviewJob(pr), linked, "- [ ] Confirm repair")) {
            gateCapture = capture.job();
            gateCapture.setStatus(AgentJobStatus.COMPLETED);
            gateCapture = agentJobRepository.saveAndFlush(gateCapture);
        }
        Feedback currentBrief = persistFeedback(
                laterNegativeRun,
                developer,
                FeedbackChannel.IN_CHAT,
                0,
                FeedbackDeliveryState.PREPARED,
                "Current guidance",
                NOW.plusSeconds(1));
        bind(currentBrief, currentNegative);
        String capturedRevision = LinkedWorkItemContentSource.currentClosingMaterialKey(
                        workspace.getId(), pr, List.of(issue))
                .orElseThrow()
                .revision()
                .value();
        assertPrimaryAdmissionWaitsAndRefuses(
                pr,
                gateCapture,
                capturedRevision,
                () -> jdbcTemplate.update(
                        "UPDATE issue SET body = ? WHERE id = ?", "Closes #18 with revised scope", pr.getId()));
        jdbcTemplate.update("UPDATE issue SET body = ? WHERE id = ?", "Closes #18", pr.getId());
        assertPrimaryAdmissionWaitsAndRefuses(
                pr,
                gateCapture,
                capturedRevision,
                () -> jdbcTemplate.update(
                        "UPDATE issue SET deleted_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(Instant.now()),
                        pr.getId()));
        jdbcTemplate.update("UPDATE issue SET deleted_at = NULL WHERE id = ?", pr.getId());
        assertPrimaryAdmissionWaitsAndRefuses(
                pr,
                gateCapture,
                capturedRevision,
                () -> jdbcTemplate.update("UPDATE issue SET state = 'OPEN' WHERE id = ?", pr.getId()));
        jdbcTemplate.update("UPDATE issue SET state = 'MERGED' WHERE id = ?", pr.getId());
        UUID capturedJobId = gateCapture.getId();
        long issueId = issue.getId();
        CountDownLatch issueWritten = new CountDownLatch(1);
        CountDownLatch releaseIssue = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = threads.submit(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE issue SET body = ? WHERE id = ?", "- [x] Confirm repair", issueId);
                issueWritten.countDown();
                awaitUninterruptibly(releaseIssue);
            }));
            assertThat(issueWritten.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> admission = threads.submit(() -> reviewedWorkChanges.linkedCaptureCurrent(
                    workspace.getId(), capturedJobId, pr.getId(), capturedRevision));
            assertThat(aBackendWaitsOnALock()).isTrue();
            releaseIssue.countDown();
            writer.get(30, TimeUnit.SECONDS);
            assertThat(admission.get(30, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseIssue.countDown();
            threads.shutdownNow();
        }
        var issueListener = new IssueAgentJobEventListener(
                agentJobService,
                issueRepository,
                pullRequestRepository,
                gate,
                workspaceResolver,
                recorder,
                transactionManager);
        for (String body : List.of("- [x] Confirm repair", "- [x] Confirm repair", "- [x] Confirm repair and test")) {
            transactions.executeWithoutResult(status -> {
                Issue current = issueRepository.findById(linkedIssueId).orElseThrow();
                current.setBody(body);
                issueRepository.saveAndFlush(current);
                var event = new ScmDomainEvent.IssueUpdated(
                        ScmEventPayload.IssueData.from(current), Set.of("body"), liveContext());
                issueListener.onIssueUpdated(event);
            });
        }
        assertThat(signalsOf(pr, ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED)).hasSize(2);
        settle(pr);
        var rows = signalsOf(pr, ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED);
        assertThat(rows)
                .filteredOn(row -> row.getState() == SignalState.TRIGGERED)
                .hasSize(1);
        assertThat(rows)
                .filteredOn(row -> row.getStateReason() == SignalStateReason.COALESCED)
                .hasSize(1);
        AgentJob recheck = jobOf(rows.stream()
                .filter(row -> row.getState() == SignalState.TRIGGERED)
                .findFirst()
                .orElseThrow());
        assertThat(signalOf(recheck)).isEqualTo(ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.value());
        assertThat(recheckedOf(recheck)).isEqualTo("[\"closing-issue-criteria\"]");
        assertThat(jobsOf(workspace))
                .containsExactlyInAnyOrder(
                        merged.getId(), laterNegativeRun.getId(), gateCapture.getId(), recheck.getId());

        try (LinkedAttempt stale = captureLinkedAttempt(recheck, linked, "- [x] Confirm repair and test")) {
            transactions.executeWithoutResult(status -> {
                Issue changed = issueRepository.findById(linkedIssueId).orElseThrow();
                changed.setBody("- [x] Confirm repair and test twice");
                issueRepository.saveAndFlush(changed);
                var newerEvent = new ScmDomainEvent.IssueUpdated(
                        ScmEventPayload.IssueData.from(changed), Set.of("body"), liveContext());
                issueListener.onIssueUpdated(newerEvent);
            });
            assertThatThrownBy(() -> admissionService.admit(stale.identity(), stale.observations()))
                    .isInstanceOf(ObservationsRefusedException.class)
                    .satisfies(error -> assertThat(((ObservationsRefusedException) error).reasonCode())
                            .isEqualTo("linked_issue_capture_stale"));
        }
        assertThat(observationRepository.findByAgentJobId(recheck.getId(), workspace.getId()))
                .isEmpty();
        assertThat(feedbackRepository.findById(oldBrief.getId()).orElseThrow().getDeliveryState())
                .isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(standing(pr)).containsExactly(currentNegative);

        SignalKey newerKey = LinkedWorkItemContentSource.currentClosingMaterialKey(
                        workspace.getId(), reload(), pullRequestRepository.findClosingIssuesById(pr.getId()))
                .orElseThrow();
        assertThat(rowOf(newerKey).getState()).isEqualTo(SignalState.DEFERRED);
        settle(pr);
        AgentJob fresh = jobOf(rowOf(newerKey));
        assertThat(recheckedOf(fresh)).isEqualTo("[\"closing-issue-criteria\"]");
        try (LinkedAttempt current = captureLinkedAttempt(fresh, linked, "- [x] Confirm repair and test twice")) {
            admissionService.admit(current.identity(), current.observations());
        }
        List<Observation> positive = observationRepository.findByAgentJobId(fresh.getId(), workspace.getId());
        Long linkedId = linked.getId();
        assertThat(positive).singleElement().satisfies(row -> {
            assertThat(row.getPractice().getId()).isEqualTo(linkedId);
            assertThat(row.getOutcome()).isEqualTo(Outcome.MET);
        });
        assertThat(observationRepository.findById(olderNegative)).isPresent();
        assertThat(observationRepository.findById(currentNegative)).isPresent();
        assertThat(observationRepository.findById(currentNegative).orElseThrow().getSupersededAt())
                .isNotNull();
        assertThat(observationRepository.findById(olderNegative).orElseThrow().getSupersededAt())
                .isNull();
        assertThat(feedbackRepository
                        .findById(currentBrief.getId())
                        .orElseThrow()
                        .getDeliveryState())
                .isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(standing(pr)).containsExactly(positive.getFirst().getId());
        assertThat(feedbackRepository.findById(oldBrief.getId()).orElseThrow().getDeliveryState())
                .isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(feedbackObservationRepository.findPreparedConversationFeedbackIdsForNegativeClaim(
                        workspace.getId(), developer.getId(), linked.getId(), ArtifactKinds.PULL_REQUEST, pr.getId()))
                .isEmpty();
        assertThat(feedbackRepository
                        .findById(deliveredCard.getId())
                        .orElseThrow()
                        .getDeliveryState())
                .isEqualTo(FeedbackDeliveryState.DELIVERED);

        fresh = agentJobRepository.findById(fresh.getId()).orElseThrow();
        var output = MAPPER.createObjectNode();
        var feedback = output.putObject("feedback");
        feedback.put(
                "admissionDigest",
                Objects.requireNonNull(fresh.getMetadata())
                        .path(ObservationAdmissionService.DIGEST_METADATA_KEY)
                        .asString());
        feedback.put("lead", "The linked criteria are complete");
        feedback.putArray("observations")
                .addObject()
                .put("id", positive.getFirst().getId().toString())
                .put("practiceSlug", linked.getSlug())
                .put("anchorable", false)
                .putArray("citations");
        feedback.putArray("units")
                .addObject()
                .put("channel", "IN_CONTEXT")
                .put("action", "NEW")
                .put("practiceSlug", linked.getSlug())
                .put("title", "Linked criteria completed")
                .put("nextStep", "Keep the completed criteria visible")
                .putObject("placement")
                .put("kind", "ARTIFACT");
        ((tools.jackson.databind.node.ObjectNode) feedback.path("units").get(0))
                .putArray("basedOn")
                .add(positive.getFirst().getId().toString());
        output.putObject("practiceCoverage").put("eligible", 1).put("evaluated", 1);
        fresh.setOutput(output);
        fresh.setStatus(AgentJobStatus.COMPLETED);
        agentJobRepository.saveAndFlush(fresh);
        handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW).deliver(fresh);
        assertThat(feedbackRepository.findByAgentJobIdAndPositionAndWorkspaceId(fresh.getId(), 0, workspace.getId()))
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM feedback WHERE workspace_id = ? AND agent_job_id = ? AND channel = 'IN_CONTEXT' AND delivery_state = 'DELIVERED'",
                        Long.class,
                        workspace.getId(),
                        fresh.getId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM feedback_dispatch WHERE workspace_id = ? AND agent_job_id = ?",
                        Long.class,
                        workspace.getId(),
                        fresh.getId()))
                .isZero();
    }

    private LinkedAttempt captureLinkedAttempt(AgentJob job, Practice linked, String expectedBody) throws Exception {
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("test-worker");
        job = agentJobRepository.saveAndFlush(job);
        var raw = folderBuilder.prepare(
                new ContextRequest.PracticeReviewRequest(job), EvidencePlan.compile(List.of(linked)));
        JobFolderIndex manifest = Objects.requireNonNull(raw.manifest());
        var snapshot = MAPPER.createObjectNode();
        snapshot.set("manifest", MAPPER.valueToTree(manifest));
        snapshot.set(
                ReviewedWork.SNAPSHOT_KEY,
                MAPPER.valueToTree(ReviewedWork.captured(
                                MAPPER.writeValueAsBytes(manifest),
                                raw.files(),
                                Objects.requireNonNull(job.getMetadata())
                                        .path("pull_request_id")
                                        .asLong(),
                                MAPPER)
                        .orElseThrow()));
        snapshot.putArray("practices")
                .addObject()
                .put("slug", linked.getSlug())
                .put("revisionId", linked.getCurrentRevision().getId());
        job.setEvidenceSnapshot(snapshot);
        job = agentJobRepository.saveAndFlush(job);
        PreparedJobInputs inputs = evidenceFiles.prepare(
                job,
                new PreparedEvidence(raw.files(), raw.filesOnDisk(), raw.cleanups(), null, raw.directories()),
                null);
        String path = SandboxLayout.CONTEXT_PREFIX + "linked_work_items/18.md";
        List<String> lines = Files.readAllLines(inputs.filesOnDisk().get(path));
        int line = java.util.stream.IntStream.range(0, lines.size())
                        .filter(index -> lines.get(index).contains(expectedBody))
                        .findFirst()
                        .orElseThrow()
                + 1;
        assertThat(lines.get(line - 1)).contains(expectedBody);
        var observations = MAPPER.createArrayNode();
        var result = observations.addObject();
        result.put("practiceSlug", linked.getSlug())
                .put("summary", "Linked criteria are complete")
                .put("outcome", "MET")
                .put("evidenceRationale", "The captured linked issue shows the completed acceptance criterion.")
                .putNull("severity");
        result.putObject("evidence")
                .putArray("citations")
                .addObject()
                .put("sourceKind", "scm.linked-work-items")
                .put("path", path)
                .put("artifactPath", path)
                .put("startLine", line)
                .put("endLine", line)
                .put("quote", expectedBody);
        return new LinkedAttempt(job, inputs, observations);
    }

    private record LinkedAttempt(AgentJob job, PreparedJobInputs inputs, JsonNode observations)
            implements AutoCloseable {
        ObservationAdmissionService.AdmissionIdentity identity() {
            return new ObservationAdmissionService.AdmissionIdentity(
                    job.getId(), job.getWorkspace().getId(), 0, "test-worker");
        }

        @Override
        public void close() {
            inputs.close();
        }
    }

    private void assertPrimaryAdmissionWaitsAndRefuses(
            PullRequest pr, AgentJob merged, String capturedRevision, Runnable update) throws Exception {
        CountDownLatch primaryLoaded = new CountDownLatch(1);
        CountDownLatch releasePrimaryReader = new CountDownLatch(1);
        CompletableFuture<Void> primaryWritten = new CompletableFuture<>();
        CountDownLatch releasePrimaryWriter = new CountDownLatch(1);
        ExecutorService primaryThreads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admission = primaryThreads.submit(() -> transactions.execute(status -> {
                pullRequestRepository.findByIdWithAllForGate(pr.getId()).orElseThrow();
                primaryLoaded.countDown();
                awaitUninterruptibly(releasePrimaryReader);
                return reviewedWorkChanges.linkedCaptureCurrent(
                        workspace.getId(), merged.getId(), pr.getId(), capturedRevision);
            }));
            assertThat(primaryLoaded.await(30, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> writer = CompletableFuture.runAsync(
                    () -> transactions.executeWithoutResult(status -> {
                        update.run();
                        primaryWritten.complete(null);
                        awaitUninterruptibly(releasePrimaryWriter);
                    }),
                    primaryThreads);
            writer.whenComplete((result, failure) -> {
                if (failure != null) primaryWritten.completeExceptionally(failure);
            });
            primaryWritten.get(30, TimeUnit.SECONDS);
            releasePrimaryReader.countDown();
            assertThat(aBackendWaitsOnALock()).isTrue();
            releasePrimaryWriter.countDown();
            writer.get(30, TimeUnit.SECONDS);
            assertThat(admission.get(30, TimeUnit.SECONDS)).isFalse();
        } finally {
            releasePrimaryReader.countDown();
            releasePrimaryWriter.countDown();
            primaryThreads.shutdownNow();
        }
    }

    private AgentJob linkedReviewJob(PullRequest pr) {
        AgentJob job = persistPullRequestReview(workspace, pr.getNumber(), pr.getId(), null);
        var metadata = Objects.requireNonNull(job.getMetadata()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) metadata)
                .put("repository_id", repository.getId())
                .put("repository_full_name", REPO)
                .put("pr_url", pr.getHtmlUrl())
                .put("title", pr.getTitle())
                .put("body", pr.getBody())
                .put("commit_sha", pr.getHeadRefOid())
                .put("source_branch", pr.getHeadRefName())
                .put("target_branch", pr.getBaseRefName())
                .put("author_id", developer.getId())
                .put("signal", ScmSignals.PULL_REQUEST_MERGED.value());
        job.setMetadata(metadata);
        job.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        job.setConfigSnapshot(Objects.requireNonNull(transactions.execute(status -> ConfigSnapshot.from(
                        memberAiPolicy
                                .binding(workspace.getId(), job.getJobType(), metadata)
                                .orElseThrow(),
                        modelResolver)
                .toJson(MAPPER))));
        job.setIntegrationKind(repositoryRepository
                .findByIdWithOrganization(repository.getId())
                .orElseThrow()
                .getProvider()
                .kind());
        return agentJobRepository.saveAndFlush(job);
    }

    private AgentJob admittedLinkedReview(PullRequest pr, Practice linked) throws Exception {
        try (LinkedAttempt capture = captureLinkedAttempt(linkedReviewJob(pr), linked, "- [ ] Confirm repair")) {
            var observations = MAPPER.createArrayNode();
            var result = (tools.jackson.databind.node.ObjectNode)
                    capture.observations().get(0).deepCopy();
            result.put("summary", "Linked criteria need confirmation")
                    .put("outcome", "NOT_MET")
                    .put("severity", "MINOR")
                    .put("evidenceRationale", "The captured criterion remains unchecked.");
            observations.add(result);
            admissionService.admit(capture.identity(), observations);
            AgentJob completed =
                    agentJobRepository.findById(capture.job().getId()).orElseThrow();
            completed.setStatus(AgentJobStatus.COMPLETED);
            completed.setCompletedAt(Instant.now());
            return agentJobRepository.saveAndFlush(completed);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReviewAPushTogetherWithAnEditAsOnePushReviewThatAlsoRechecks(boolean gitLab) {
        provider(gitLab);
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        practice("ships-tests-with-the-change", ScmSignals.PULL_REQUEST_SYNCHRONIZED);
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);

        push(NEXT_HEAD);
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
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        String explained = "Adds the thing because reviewers could not tell why";
        edit(explained, Set.of("body"));
        settle(pr);
        push(NEXT_HEAD);
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
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
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
                upsert(false, HEAD, "Adds the thing because reviewers could not tell why");
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
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
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
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
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
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing because reviewers could not tell why");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
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
        Practice abstained = practice("abstained", ScmSignals.PULL_REQUEST_OPENED);
        Practice obsolete = practice("obsolete", ScmSignals.PULL_REQUEST_OPENED);
        Practice superseded = practice("superseded", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(answered, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        observe(abstained, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        observe(obsolete, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        UUID supersededId = observe(superseded, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        jdbcTemplate.update(
                "UPDATE observation SET superseded_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.plusSeconds(90)),
                supersededId);
        obsolete.setCriteria("Changed review criteria");
        obsolete.setCurrentRevision(practiceRevisionRepository.save(
                new de.tum.cit.aet.hephaestus.practices.model.PracticeRevision(obsolete, 2)));
        practiceRepository.saveAndFlush(obsolete);
        UUID invalid = observe(withdrawn, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        observe(unanswered, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        AgentJob ready = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW.plusSeconds(60));
        observe(answered, ready, pr.getId(), developer, Outcome.MET, null, NOW.plusSeconds(60));
        observe(abstained, ready, pr.getId(), developer, Outcome.NOT_APPLICABLE, null, NOW.plusSeconds(60));
        invalidationRepository.save(new ObservationInvalidation(
                observationRepository.findById(invalid).orElseThrow(), 1L, "Wrong when made", NOW.plusSeconds(90)));

        pr.setBody("A material description repair");
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
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        AgentJob recheck = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW.plusSeconds(60));
        observe(sized, recheck, pr.getId(), developer, Outcome.MET, null, NOW.plusSeconds(60));

        pr.setBody("A material description repair");
        assertThat(rechecked(revision(pr))).containsExactly("describe-what-and-why");
        pr.setDraft(true);
        assertThat(revision(pr)).isInstanceOf(GateDecision.Skip.class);
        pr.setDraft(false);
        pr.setState(Issue.State.MERGED);
        assertThat(revision(pr)).isInstanceOf(GateDecision.Skip.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNotAdmitADeferredInitialEditAfterReadyReviewedTheSameCapturedWork(boolean gitLab) {
        provider(gitLab);
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_READY);
        PullRequest pr = pullRequest(false, HEAD, "Before the initial edit");
        edit("Adds the thing", Set.of("body"));
        SignalKey initialEdit = currentKey(pr, ScmSignals.PULL_REQUEST_EDITED);
        AgentJob ready = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        UUID negative = observe(describe, ready, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);

        settle(pr);

        assertThat(rowOf(initialEdit).getJobId()).isNull();
        assertThat(jobsOf(workspace)).containsExactly(ready.getId());
        assertThat(standing(pr)).containsExactly(negative);

        edit("Adds the thing because it repairs the missing motivation", Set.of("body"));
        settle(pr);
        AgentJob repair = jobOf(rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED)));
        assertThat(recheckedOf(repair)).isEqualTo("[\"describe-what-and-why\"]");
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(ready.getId(), repair.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepExplicitOccasionPracticesWhenTheImplicitRepairHasTheSameCapture(boolean synchronizedWork) {
        SignalName signal = synchronizedWork ? ScmSignals.PULL_REQUEST_SYNCHRONIZED : ScmSignals.PULL_REQUEST_EDITED;
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_READY);
        practice("states-how-to-verify-the-change", signal);
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        if (synchronizedWork) {
            push(HEAD);
        } else {
            edit("Adds the thing", Set.of("body"));
        }
        AgentJob ready = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(describe, ready, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);

        settle(pr);

        AgentJob editReview = jobOf(rowOf(currentKey(pr, signal)));
        assertThat(Objects.requireNonNull(editReview.getMetadata()).has(AgentJob.RECHECKED_PRACTICES_METADATA_KEY))
                .isFalse();
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(ready.getId(), editReview.getId());
    }

    @Test
    void shouldRecheckOnlyTheNegativeWhoseOwnCaptureChanged() {
        Practice olderProblem = practice("states-how-to-verify-the-change", ScmSignals.PULL_REQUEST_OPENED);
        Practice sameWorkProblem = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_READY);
        PullRequest pr = pullRequest(false, HEAD, "Before the edit");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        observe(olderProblem, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        edit("Adds the thing", Set.of("body"));
        AgentJob ready = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW.plusSeconds(60));
        observe(sameWorkProblem, ready, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW.plusSeconds(60));

        settle(pr);

        AgentJob repair = jobOf(rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED)));
        assertThat(recheckedOf(repair)).isEqualTo("[\"states-how-to-verify-the-change\"]");
        assertThat(jobsOf(workspace)).containsExactlyInAnyOrder(opened.getId(), ready.getId(), repair.getId());
    }

    @Test
    void shouldNotInferAMaterialRepairFromAReviewWithoutStagedWorkProvenance() {
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD, "Before the edit");
        AgentJob unknown = persistPullRequestReview(workspace, pr.getNumber(), pr.getId(), NOW);
        UUID negative = observe(describe, unknown, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        edit("Adds the thing because it fixes the missing motivation", Set.of("body"));

        settle(pr);

        assertThat(rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED)).getJobId())
                .isNull();
        assertThat(jobsOf(workspace)).containsExactly(unknown.getId());
        assertThat(standing(pr)).containsExactly(negative);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldLeaveTheEarlierObservationStandingWhenTheRepairCaptureFails(boolean gitLab) {
        provider(gitLab);
        Practice describe = practice("describe-what-and-why", ScmSignals.PULL_REQUEST_OPENED);
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob opened = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        UUID negative = observe(describe, opened, pr.getId(), developer, Outcome.NOT_MET, Severity.MINOR, NOW);
        edit("Adds the thing because it fixes the missing motivation", Set.of("body"));
        settle(pr);
        AgentJob repair = jobOf(rowOf(currentKey(pr, ScmSignals.PULL_REQUEST_EDITED)));

        var handlers = mock(JobTypeHandlerRegistry.class);
        var handler = mock(JobTypeHandler.class);
        when(handlers.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
        when(handler.prepareInputs(any())).thenThrow(new JobPreparationException("Pinned review head is unavailable"));
        var sandbox = mock(SandboxManager.class);
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(workspace.getId())).thenReturn(LlmBudgetDecision.ALLOWED);
        var meters = new SimpleMeterRegistry();
        try {
            var executor = new AgentJobExecutor(
                    new AgentProperties(
                            true,
                            Duration.ofSeconds(1),
                            5,
                            5,
                            Duration.ofSeconds(25),
                            Duration.ofDays(14),
                            Duration.ofDays(90)),
                    agentJobRepository,
                    memberAiPolicy,
                    handlers,
                    mock(JobEvidenceFiles.class),
                    mock(PracticePiAdapter.class),
                    mock(WorkerJwtIssuer.class),
                    sandbox,
                    new TaskExecutorAdapter(Runnable::run),
                    transactions,
                    MAPPER,
                    meters,
                    new PracticeReviewRefusalMetrics(meters),
                    new AgentJobTelemetry(meters, Tracer.NOOP),
                    mock(LlmUsageRecorder.class),
                    budgets,
                    null,
                    Optional.empty(),
                    Optional.empty());
            assertThat(executor.processJob(repair.getId())).isTrue();
        } finally {
            meters.close();
        }
        AgentJob failed = agentJobRepository
                .findByIdAndWorkspaceId(repair.getId(), workspace.getId())
                .orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("Pinned review head is unavailable");
        assertThat(failed.getEvidenceSnapshot()).isNull();
        assertThat(failed.getDeliveryStatus()).isNotEqualTo(DeliveryStatus.DELIVERED);
        assertThat(observationRepository.findByAgentJobId(repair.getId(), workspace.getId()))
                .isEmpty();
        assertThat(standing(pr)).containsExactly(negative);
        verify(handler).prepareInputs(any());
        verifyNoInteractions(sandbox);
    }

    @Autowired
    private ReviewMemberAiPolicy memberAiPolicy;

    private void provider(boolean gitLab) {
        if (!gitLab) return;
        repository.setProvider(ensureGitLabProvider());
        repository.setHtmlUrl("https://gitlab.com/" + REPO);
        repository = repositoryRepository.saveAndFlush(repository);
        developer.setProvider(ensureGitLabProvider());
        developer = userRepository.saveAndFlush(developer);
    }

    private List<UUID> standing(PullRequest pr) {
        return LatestRun.perClaim(observationRepository.findStandingForWork(
                        workspace.getId(), ScmSignals.PULL_REQUEST, pr.getId(), developer.getId()))
                .stream()
                .map(Observation::getId)
                .toList();
    }

    private AgentJob capturedReview(Workspace owner, int number, long artifactId, Instant completedAt) {
        AgentJob job = persistPullRequestReview(owner, number, artifactId, completedAt);
        PullRequest pr = reload();
        var manifest = ReviewedWorkFixtures.pullRequestManifest(completedAt, pr.getBody(), pr.getHeadRefOid());
        var work = ReviewedWork.captured(
                        MAPPER.writeValueAsBytes(manifest),
                        Map.of(
                                SandboxLayout.CONTEXT_PREFIX + "metadata.json",
                                ReviewedWorkFixtures.metadata(MAPPER, pr.getTitle(), pr.getBody(), pr.getHeadRefOid())),
                        artifactId,
                        MAPPER)
                .orElseThrow();
        var snapshot = MAPPER.createObjectNode();
        snapshot.set("manifest", MAPPER.valueToTree(manifest));
        snapshot.set(ReviewedWork.SNAPSHOT_KEY, MAPPER.valueToTree(work));
        job.setEvidenceSnapshot(snapshot);
        return agentJobRepository.saveAndFlush(job);
    }

    @Test
    void shouldReadACapturedRevisionOnlyWithinTheJobsWorkspace() {
        PullRequest pr = pullRequest(false, HEAD, "Adds the thing");
        AgentJob job = capturedReview(workspace, pr.getNumber(), pr.getId(), NOW);
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("repair-other"));
        assertThat(agentJobRepository.findCapturedReviewedWork(workspace.getId(), Set.of(job.getId())))
                .singleElement()
                .satisfies(row -> assertThat(row.getReviewedWork()).isNotNull());
        assertThat(agentJobRepository.findCapturedReviewedWork(other.getId(), Set.of(job.getId())))
                .isEmpty();
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

    private Practice practice(String slug, SignalName occasion) {
        return practice(workspace, slug, occasion);
    }

    private Practice practice(Workspace workspace, String slug, SignalName occasion) {
        Practice practice = persistPractice(workspace, null, slug, slug, null);
        PracticeTestEvidence.configure(practice, occasion);
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
