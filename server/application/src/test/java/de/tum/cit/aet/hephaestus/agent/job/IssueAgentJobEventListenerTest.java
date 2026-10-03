package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.practices.review.GateDecisionTestFixtures.automaticRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentProvenance;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class IssueAgentJobEventListenerTest extends BaseUnitTest {

    private static final RepositoryRef REPO_REF = new RepositoryRef(100L, "owner/repo", "main");
    private static final Long ISSUE_ID = 789L;
    private static final int ISSUE_NUMBER = 7;
    private static final Long REPO_ID = 100L;
    private static final Long WORKSPACE_ID = 1L;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private ReviewGate reviewGate;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private SignalRecorder signalRecorder;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private IssueCommentRepository issueCommentRepository;

    private IssueAgentJobEventListener listener;

    private Workspace owningWorkspace;

    @BeforeEach
    void setUp() {
        listener = new IssueAgentJobEventListener(
                agentJobService,
                issueRepository,
                pullRequestRepository,
                reviewGate,
                workspaceResolver,
                signalRecorder,
                new IssueEvidenceRevision(issueCommentRepository, new IssueCommentProvenance(issueId -> List.of())),
                transactionManager);
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        owningWorkspace = new Workspace();
        owningWorkspace.setId(WORKSPACE_ID);
        // Every dispatching path asks two questions before it may do anything: who owns this
        // repository, and is this observation ours to act on. Lenient because the paths that
        // short-circuit before dispatch never ask them.
        lenient().when(workspaceResolver.resolveAllForRepository(any())).thenReturn(List.of(owningWorkspace));
        lenient().when(signalRecorder.record(any(), any(), any())).thenReturn(true);
    }

    private ScmEventPayload.IssueData createIssueData(Issue.State state) {
        return new ScmEventPayload.IssueData(
                ISSUE_ID,
                ISSUE_NUMBER,
                "Test issue",
                "body",
                state,
                null,
                "https://github.com/owner/repo/issues/7",
                false,
                REPO_REF,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null);
    }

    private EventContext webhookContext(Long scopeId) {
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                scopeId,
                REPO_REF,
                DataSource.WEBHOOK,
                "opened",
                UUID.randomUUID().toString(),
                null);
    }

    private EventContext syncContext() {
        return EventContext.forSync(1L, REPO_REF);
    }

    private Issue createIssue(Issue.State state) {
        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setNumber(ISSUE_NUMBER);
        issue.setTitle("Test issue");
        issue.setBody("body");
        issue.setState(state);
        issue.setUpdatedAt(Instant.now());

        Repository repo = new Repository();
        repo.setId(REPO_ID);
        repo.setNameWithOwner("owner/repo");
        issue.setRepository(repo);
        return issue;
    }

    private Issue setupHappyPath() {
        Issue issue = createIssue(Issue.State.OPEN);
        when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));

        var run = automaticRun(owningWorkspace, List.of());
        when(reviewGate.evaluateIssue(eq(issue), eq(owningWorkspace), any(), any()))
                .thenReturn(run);
        when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        return issue;
    }

    private IssueReviewSubmissionRequest captureSubmission(Long workspaceId) {
        var captor = ArgumentCaptor.forClass(IssueReviewSubmissionRequest.class);
        verify(agentJobService).submit(eq(workspaceId), eq(AgentJobType.ISSUE_REVIEW), captor.capture(), any(), any());
        return captor.getValue();
    }

    @Nested
    class TombstonedWorkTests {

        @ParameterizedTest
        @ValueSource(strings = {"created", "closed"})
        void shouldHoldOccasionBeforeAdmissionWhenIssueIsTombstoned(String event) {
            Issue.State state = event.equals("closed") ? Issue.State.CLOSED : Issue.State.OPEN;
            Issue issue = createIssue(state);
            issue.setDeletedAt(Instant.now());
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));
            var data = createIssueData(state);
            var context = webhookContext(WORKSPACE_ID);
            switch (event) {
                case "created" -> listener.onIssueCreated(new ScmDomainEvent.IssueCreated(data, context));
                case "closed" -> listener.onIssueClosed(new ScmDomainEvent.IssueClosed(data, null, context));
                default -> throw new AssertionError(event);
            }

            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.ARTIFACT_NOT_VISIBLE));
            verifyNoInteractions(reviewGate, agentJobService);
        }
    }

    @Nested
    class FilteringTests {

        @Test
        void shouldRecordSyncDiscoveredSignalsWithoutReviewingThem() {
            // Recording is unconditional; triggering is policy. Reconciliation establishes THAT the issue
            // was opened, which is why the row is written — but replaying a repository's whole history as
            // live coaching is not what a sync was asked to do, so nothing else runs.
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, syncContext());

            listener.onIssueCreated(event);

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(issueRepository, never()).findByIdWithRepositoryAndAssignees(anyLong());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @Test
        void shouldSkipClosedIssues() {
            var issueData = createIssueData(Issue.State.CLOSED);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            listener.onIssueCreated(event);

            verify(issueRepository, never()).findByIdWithRepositoryAndAssignees(anyLong());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenIssueNotFound() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.empty());

            listener.onIssueCreated(event);

            // The signal was claimed before the artifact was read, so a vanished issue must be settled
            // rather than left pending for a reaper to re-offer forever.
            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.ARTIFACT_GONE));
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenIssueHasNullRepository() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            Issue issue = createIssue(Issue.State.OPEN);
            ReflectionTestUtils.setField(issue, "repository", null);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));

            listener.onIssueCreated(event);

            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.ARTIFACT_GONE));
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }
    }

    @Nested
    class GateIntegrationTests {

        @Test
        void shouldSkipWhenGateReturnsSkip() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            Issue issue = createIssue(Issue.State.OPEN);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));
            when(reviewGate.evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO))
                    .thenReturn(new GateDecision.Skip("no matching practices"));

            listener.onIssueCreated(event);

            // A workspace's own gate declining is an answer, not an omission — it settles the signal so
            // the refusal is countable instead of invisible.
            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.GATE_SKIPPED));
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @Test
        void shouldSubmitWhenGateReturnsRun() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(99L));

            setupHappyPath();

            listener.onIssueCreated(event);

            assertThat(captureSubmission(WORKSPACE_ID).triggerSignal()).isEqualTo(ScmSignals.ISSUE_OPENED);
        }

        @Test
        void shouldUseWorkspaceIdFromGateNotContext() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(99L));

            Issue issue = createIssue(Issue.State.OPEN);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));

            Workspace workspace = new Workspace();
            workspace.setId(42L);
            var run = automaticRun(workspace, List.of());
            when(reviewGate.evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO))
                    .thenReturn(run);
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            listener.onIssueCreated(event);

            var workspaceIdCaptor = ArgumentCaptor.forClass(Long.class);
            verify(agentJobService)
                    .submit(
                            workspaceIdCaptor.capture(),
                            eq(AgentJobType.ISSUE_REVIEW),
                            any(IssueReviewSubmissionRequest.class),
                            any(),
                            any());
            assertThat(workspaceIdCaptor.getValue()).isEqualTo(42L).isNotEqualTo(99L);
        }

        @Test
        void shouldBuildCorrectSubmissionRequest() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            Issue issue = createIssue(Issue.State.OPEN);
            Instant updatedAt = Instant.parse("2026-01-01T00:00:00Z");
            issue.setUpdatedAt(updatedAt);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            var run = automaticRun(workspace, List.of());
            when(reviewGate.evaluateIssue(eq(issue), eq(owningWorkspace), any(), any()))
                    .thenReturn(run);
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            listener.onIssueCreated(event);

            var captor = ArgumentCaptor.forClass(IssueReviewSubmissionRequest.class);
            verify(agentJobService)
                    .submit(eq(WORKSPACE_ID), eq(AgentJobType.ISSUE_REVIEW), captor.capture(), any(), any());

            IssueReviewSubmissionRequest request = captor.getValue();
            assertThat(request.issueId()).isEqualTo(ISSUE_ID);
            assertThat(request.issueNumber()).isEqualTo(ISSUE_NUMBER);
            assertThat(request.repositoryId()).isEqualTo(REPO_ID);
            assertThat(request.repositoryFullName()).isEqualTo("owner/repo");
            assertThat(request.title()).isEqualTo("Test issue");
            assertThat(request.body()).isEqualTo("body");
            assertThat(request.state()).isEqualTo("OPEN");
            assertThat(request.updatedAt()).isEqualTo(updatedAt);
        }

        @Test
        void shouldDefaultBodyToEmptyWhenNull() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            Issue issue = createIssue(Issue.State.OPEN);
            issue.setBody(null);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(reviewGate.evaluateIssue(eq(issue), eq(owningWorkspace), any(), any()))
                    .thenReturn(automaticRun(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            listener.onIssueCreated(event);

            var captor = ArgumentCaptor.forClass(IssueReviewSubmissionRequest.class);
            verify(agentJobService)
                    .submit(eq(WORKSPACE_ID), eq(AgentJobType.ISSUE_REVIEW), captor.capture(), any(), any());
            assertThat(captor.getValue().body()).isEmpty();
        }

        @Test
        void shouldPassIssueCreatedTriggerEventName() {
            Issue issue = setupHappyPath();
            var issueData = createIssueData(Issue.State.OPEN);

            listener.onIssueCreated(new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L)));

            verify(reviewGate).evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO);
        }

        @Test
        void shouldNotPropagateExceptionsFromGate() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            Issue issue = createIssue(Issue.State.OPEN);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));
            when(reviewGate.evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO))
                    .thenThrow(new RuntimeException("DB connectivity error"));

            listener.onIssueCreated(event);

            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @Test
        void shouldNotPropagateExceptionsFromSubmit() {
            var issueData = createIssueData(Issue.State.OPEN);
            var event = new ScmDomainEvent.IssueCreated(issueData, webhookContext(1L));

            Issue issue = createIssue(Issue.State.OPEN);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(reviewGate.evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO))
                    .thenReturn(automaticRun(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("submission failed"));

            // Swallowing is the contract: this listener runs on the webhook consumer, and a thrown
            // exception would NAK the delivery and redeliver the same doomed submission.
            assertThatCode(() -> listener.onIssueCreated(event)).doesNotThrowAnyException();
        }
    }

    @Nested
    class RetrospectiveIssueClosedTests {

        @Test
        void onIssueClosed_routesThroughGateAndCarriesTheClosedTriggerOntoTheJob() {
            Issue issue = createIssue(Issue.State.CLOSED);
            when(issueRepository.findByIdWithRepositoryAndAssignees(ISSUE_ID)).thenReturn(Optional.of(issue));
            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(reviewGate.evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_CLOSED, TriggerMode.AUTO))
                    .thenReturn(automaticRun(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            var issueData = createIssueData(Issue.State.CLOSED);
            listener.onIssueClosed(new ScmDomainEvent.IssueClosed(issueData, "completed", webhookContext(1L)));

            verify(reviewGate).evaluateIssue(issue, owningWorkspace, ScmSignals.ISSUE_CLOSED, TriggerMode.AUTO);
            assertThat(captureSubmission(WORKSPACE_ID).triggerSignal()).isEqualTo(ScmSignals.ISSUE_CLOSED);
        }

        @Test
        void onIssueClosed_recordsSyncDiscoveredClosesWithoutReviewingThem() {
            // The sync-trigger guard: a history replay must NOT fire a retrospective review for every issue
            // the repository ever closed. Without it, one sync = a mass-replay job storm.
            var issueData = createIssueData(Issue.State.CLOSED);
            listener.onIssueClosed(new ScmDomainEvent.IssueClosed(issueData, "completed", syncContext()));

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(issueRepository, never()).findByIdWithRepositoryAndAssignees(anyLong());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }
    }

    @Nested
    class IssueUpdatedTests {

        @Test
        void shouldDeferTheSameUpdateForEveryMonitoringWorkspace() {
            Workspace second = new Workspace();
            second.setId(8L);
            when(workspaceResolver.resolveAllForRepository(any())).thenReturn(List.of(owningWorkspace, second));
            var issueData = createIssueData(Issue.State.OPEN);
            var context = webhookContext(1L);

            listener.onIssueUpdated(new ScmDomainEvent.IssueUpdated(issueData, Set.of("title"), context));

            verify(signalRecorder)
                    .defer(
                            ScmSignals.issueKey(WORKSPACE_ID, ScmSignals.ISSUE_UPDATED, issueData)
                                    .orElseThrow(),
                            context.occurredAt(),
                            null);
            verify(signalRecorder)
                    .defer(
                            ScmSignals.issueKey(8L, ScmSignals.ISSUE_UPDATED, issueData)
                                    .orElseThrow(),
                            context.occurredAt(),
                            null);
        }

        @Test
        void shouldKeepTheEditorDistinctFromTheReviewedIssueAuthor() {
            var issueData = createIssueData(Issue.State.OPEN);
            var base = webhookContext(1L);
            var context = new EventContext(
                    base.eventId(),
                    base.occurredAt(),
                    base.scopeId(),
                    base.repository(),
                    base.source(),
                    base.webhookAction(),
                    base.correlationId(),
                    base.providerType(),
                    99L);

            listener.onIssueUpdated(new ScmDomainEvent.IssueUpdated(issueData, Set.of("title"), context));

            verify(signalRecorder)
                    .defer(
                            ScmSignals.issueKey(WORKSPACE_ID, ScmSignals.ISSUE_UPDATED, issueData)
                                    .orElseThrow(),
                            context.occurredAt(),
                            99L);
        }

        @ParameterizedTest
        @EnumSource(
                value = Issue.State.class,
                names = {"OPEN", "CLOSED"})
        void shouldDeferUpdatesWithoutReadingTheMirrorOrSubmitting(Issue.State state) {
            var issueData = createIssueData(state);
            var context = webhookContext(1L);
            listener.onIssueUpdated(new ScmDomainEvent.IssueUpdated(issueData, Set.of("relationships"), context));

            verify(signalRecorder)
                    .defer(
                            ScmSignals.issueKey(WORKSPACE_ID, ScmSignals.ISSUE_UPDATED, issueData)
                                    .orElseThrow(),
                            context.occurredAt(),
                            null);
            verifyNoInteractions(pullRequestRepository);
            verify(issueRepository, never()).findByIdWithRepositoryAndAssignees(anyLong());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = Issue.State.class,
                names = {"OPEN", "CLOSED"})
        void shouldUseTheSameDurableIdentityForReplayedUpdates(Issue.State state) {
            var issueData = createIssueData(state);
            var context = webhookContext(1L);
            listener.onIssueUpdated(new ScmDomainEvent.IssueUpdated(issueData, Set.of("title"), context));
            listener.onIssueUpdated(new ScmDomainEvent.IssueUpdated(issueData, Set.of("title"), context));

            ArgumentCaptor<SignalKey> keys = ArgumentCaptor.forClass(SignalKey.class);
            verify(signalRecorder, times(2)).defer(keys.capture(), any(), any());
            assertThat(keys.getAllValues().get(1)).isEqualTo(keys.getAllValues().getFirst());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
        }

        @Test
        void shouldPropagateRecordingFailureSoTheMirrorTransactionCannotCommit() {
            var event = new ScmDomainEvent.IssueUpdated(
                    createIssueData(Issue.State.OPEN), Set.of("title"), webhookContext(1L));
            when(signalRecorder.defer(any(), any(), any()))
                    .thenThrow(new IllegalStateException("database unavailable"));
            assertThatThrownBy(() -> listener.onIssueUpdated(event)).isInstanceOf(IllegalStateException.class);
        }

        /**
         * A lock, a pin or a comment count moves nothing the issue evidence is built from, so every
         * practice bound to the occasion would read byte-identical evidence and republish the conclusion
         * it already reached. The mirror still records the change; the review pipeline never hears of it.
         */
        @ParameterizedTest
        @EnumSource(
                value = Issue.State.class,
                names = {"OPEN", "CLOSED"})
        void shouldNotOccasionAReviewForAnUpdateThatMovedNoReviewableField(Issue.State state) {
            var issueData = createIssueData(state);

            listener.onIssueUpdated(
                    new ScmDomainEvent.IssueUpdated(issueData, Set.of("locked", "commentsCount"), webhookContext(1L)));

            verify(signalRecorder, never()).record(any(), any(), any());
            verify(signalRecorder, never()).defer(any(), any(), any());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = Issue.State.class,
                names = {"OPEN", "CLOSED"})
        void shouldRecordSyncDiscoveredSignalsWithoutReviewingThem(Issue.State state) {
            // Same split as the created path: an update caught up with by reconciliation is recorded and
            // not coached on.
            var issueData = createIssueData(state);
            var event = new ScmDomainEvent.IssueUpdated(issueData, Set.of("relationships"), syncContext());

            listener.onIssueUpdated(event);

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(issueRepository, never()).findByIdWithRepositoryAndAssignees(anyLong());
            verify(reviewGate, never()).evaluateIssue(any(), any(Workspace.class), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any(), any());
        }

        @Test
        void shouldKeepClosedIssueAndDependentMergeRequestUpdatesSeparate() {
            Issue closing = createIssue(Issue.State.CLOSED);
            PullRequest merged = new PullRequest();
            merged.setId(99L);
            merged.setRepository(closing.getRepository());
            merged.setTitle("Implement the outcome");
            merged.setBody("Closes #7");
            merged.setHeadRefOid("abc123");
            when(pullRequestRepository.findMergedClosingPullRequestIdsByIssueId(ISSUE_ID))
                    .thenReturn(List.of(99L));
            when(pullRequestRepository.findByIdWithAllForGate(99L)).thenReturn(Optional.of(merged));
            when(pullRequestRepository.findClosingIssuesById(99L)).thenReturn(List.of(closing));
            var context = webhookContext(1L);

            listener.onIssueUpdated(
                    new ScmDomainEvent.IssueUpdated(createIssueData(Issue.State.CLOSED), Set.of("body"), context));

            var keys = ArgumentCaptor.forClass(SignalKey.class);
            verify(signalRecorder, times(2)).defer(keys.capture(), eq(context.occurredAt()), eq(null));
            assertThat(keys.getAllValues())
                    .extracting(SignalKey::signalName)
                    .containsExactly(ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED, ScmSignals.ISSUE_UPDATED);
            assertThat(keys.getAllValues()).extracting(SignalKey::artifactId).containsExactly(99L, ISSUE_ID);
            verifyNoInteractions(agentJobService);
        }
    }
}
