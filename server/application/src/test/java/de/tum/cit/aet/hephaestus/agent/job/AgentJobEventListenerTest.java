package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.practices.review.GateDecisionTestFixtures.automaticDetection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.practices.spi.UserRoleChecker;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import tools.jackson.databind.ObjectMapper;

class AgentJobEventListenerTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RepositoryRef REPO_REF = new RepositoryRef(100L, "owner/repo", "main");
    private static final Long PR_ID = 456L;
    private static final int PR_NUMBER = 42;
    private static final Long WORKSPACE_ID = 1L;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PracticeReviewDetectionGate practiceReviewDetectionGate;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private SignalRecorder signalRecorder;

    private AgentJobEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new AgentJobEventListener(
                agentJobService, pullRequestRepository, practiceReviewDetectionGate, workspaceResolver, signalRecorder);

        Workspace owningWorkspace = new Workspace();
        owningWorkspace.setId(WORKSPACE_ID);
        // Lenient: the paths that short-circuit before dispatch never ask these questions.
        lenient().when(workspaceResolver.resolveForRepository(any())).thenReturn(Optional.of(owningWorkspace));
        lenient().when(signalRecorder.record(any(), any(), any())).thenReturn(true);
        lenient().when(pullRequestRepository.findHeadRefOidById(any())).thenReturn(Optional.of("abc123"));
    }

    // Helpers

    private ScmEventPayload.PullRequestData createPrData(Issue.State state, boolean isDraft, boolean isMerged) {
        return new ScmEventPayload.PullRequestData(
                PR_ID,
                PR_NUMBER,
                "Test PR",
                "body",
                state,
                isDraft,
                isMerged,
                0,
                0,
                0,
                "https://github.com/owner/repo/pull/42",
                REPO_REF,
                null,
                null,
                null,
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

    /**
     * Creates a real PullRequest with the fields the listener reads set — not all fields
     * are accessed depending on the code path (e.g., closed PR skips branch info checks).
     */
    private PullRequest mockPullRequest(
            @Nullable String headRefOid, @Nullable String headRefName, @Nullable String baseRefName) {
        PullRequest pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setBaseRefOid("a".repeat(40));
        org.springframework.test.util.ReflectionTestUtils.setField(pr, "headRefOid", headRefOid);
        org.springframework.test.util.ReflectionTestUtils.setField(pr, "headRefName", headRefName);
        org.springframework.test.util.ReflectionTestUtils.setField(pr, "baseRefName", baseRefName);
        pr.setState(Issue.State.OPEN);
        pr.setMerged(false);
        return pr;
    }

    private ScmEventPayload.ReviewData createReviewData() {
        return new ScmEventPayload.ReviewData(
                100L,
                "LGTM",
                PullRequestReview.State.APPROVED,
                false,
                "https://github.com/owner/repo/pull/42#pullrequestreview-100",
                200L,
                PR_ID,
                Instant.now(),
                100L);
    }

    private PullRequest setupHappyPath() {
        PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        var detect = automaticDetection(workspace, List.of());
        when(practiceReviewDetectionGate.evaluate(eq(pr), any(), any())).thenReturn(detect);
        when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        return pr;
    }

    private PullRequestReviewSubmissionRequest captureSubmission(Long workspaceId) {
        var captor = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
        verify(agentJobService)
                .submit(eq(workspaceId), eq(AgentJobType.PULL_REQUEST_REVIEW), captor.capture(), any(), any());
        assertThat(captor.getValue().baseRefOid()).isEqualTo("a".repeat(40));
        return captor.getValue();
    }

    // Test Groups

    @Nested
    class TombstonedWorkTests {

        @ParameterizedTest
        @ValueSource(strings = {"created", "ready", "synchronized", "merged", "closed", "reviewed"})
        void shouldHoldOccasionBeforeAdmissionWhenPullRequestIsTombstoned(String event) {
            PullRequest pr = mockPullRequest("abc123", "feature", "main");
            Repository repository = new Repository();
            repository.setId(REPO_REF.id());
            repository.setNameWithOwner(REPO_REF.nameWithOwner());
            pr.setRepository(repository);
            pr.setTitle("Test PR");
            pr.setDeletedAt(Instant.now());
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            boolean merged = event.equals("merged");
            Issue.State state = merged || event.equals("closed") ? Issue.State.CLOSED : Issue.State.OPEN;
            pr.setState(state);
            pr.setMerged(merged);
            var data = createPrData(state, false, merged);
            var context = webhookContext(WORKSPACE_ID);
            switch (event) {
                case "created" -> listener.onPullRequestCreated(new ScmDomainEvent.PullRequestCreated(data, context));
                case "ready" -> listener.onPullRequestReady(new ScmDomainEvent.PullRequestReady(data, context));
                case "synchronized" ->
                    listener.onPullRequestSynchronized(new ScmDomainEvent.PullRequestSynchronized(data, context));
                case "merged" -> listener.onPullRequestMerged(new ScmDomainEvent.PullRequestMerged(data, context));
                case "closed" ->
                    listener.onPullRequestClosed(new ScmDomainEvent.PullRequestClosed(data, false, context));
                case "reviewed" ->
                    listener.onReviewSubmitted(new ScmDomainEvent.ReviewSubmitted(createReviewData(), context));
                default -> throw new AssertionError(event);
            }

            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.ARTIFACT_NOT_VISIBLE));
            verifyNoInteractions(practiceReviewDetectionGate, agentJobService);
        }

        /**
         * Pins the order against the branch-info check: missing refs leave the occasion RECORDED, which
         * would silently swallow the hold if the tombstone check moved below it.
         */
        @Test
        void shouldHoldTombstonedPullRequestEvenWhenBranchInformationIsMissing() {
            PullRequest pr = mockPullRequest(null, null, null);
            pr.setDeletedAt(Instant.now());
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onPullRequestCreated(new ScmDomainEvent.PullRequestCreated(
                    createPrData(Issue.State.OPEN, false, false), webhookContext(WORKSPACE_ID)));

            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.ARTIFACT_NOT_VISIBLE));
            verifyNoInteractions(practiceReviewDetectionGate, agentJobService);
        }
    }

    @Nested
    class FilteringTests {

        @Test
        void shouldRecordSyncDiscoveredSignalsWithoutReviewingThem() {
            // Recording is unconditional; triggering is policy — replaying history as live coaching is
            // not what a sync was asked to do.
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, syncContext());

            listener.onPullRequestCreated(event);

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipClosedPRs() {
            var prData = createPrData(Issue.State.CLOSED, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            listener.onPullRequestCreated(event);

            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipMergedPRs() {
            var prData = createPrData(Issue.State.MERGED, false, true);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            listener.onPullRequestCreated(event);

            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenIsMergedTrueButStateIsOpen() {
            // Race condition: merge flag set before state update in webhook
            var prData = createPrData(Issue.State.OPEN, false, true);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            listener.onPullRequestCreated(event);

            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenPRNotFound() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.empty());

            listener.onPullRequestCreated(event);

            // The signal was claimed before the artifact was read, so a vanished PR must be settled
            // rather than left pending for a reaper to re-offer forever.
            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.ARTIFACT_GONE));
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenHeadRefOidIsNull() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest(null, "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onPullRequestCreated(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenHeadRefNameIsNull() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", null, "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onPullRequestCreated(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSubmitWithoutAPinnedBaseWhenTheProviderGaveNone() {
            // A GitLab webhook carries no base SHA; preparation resolves the target branch instead.
            var event = new ScmDomainEvent.PullRequestCreated(
                    createPrData(Issue.State.OPEN, false, false), webhookContext(1L));
            PullRequest pr = setupHappyPath();
            org.springframework.test.util.ReflectionTestUtils.setField(pr, "baseRefOid", null);
            listener.onPullRequestCreated(event);
            var captor = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
            verify(agentJobService)
                    .submit(eq(WORKSPACE_ID), eq(AgentJobType.PULL_REQUEST_REVIEW), captor.capture(), any(), any());
            assertThat(captor.getValue().baseRefOid()).isNull();
            assertThat(captor.getValue().baseRefName()).isEqualTo("main");
        }

        @Test
        void shouldSkipWhenBaseRefNameIsNull() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", null);
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onPullRequestCreated(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }
    }

    @Nested
    class GateIntegrationTests {

        @Test
        void shouldSkipWhenGateReturnsSkip() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                    .thenReturn(new GateDecision.Skip("no matching practices"));

            listener.onPullRequestCreated(event);

            // A workspace's own gate declining is an answer, not an omission — it settles the signal so
            // the refusal is countable instead of invisible.
            verify(signalRecorder).markRefused(any(), eq(SignalStateReason.GATE_SKIPPED));
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSubmitWhenGateReturnsDetect() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(99L));

            setupHappyPath();

            listener.onPullRequestCreated(event);

            assertThat(captureSubmission(WORKSPACE_ID).triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_OPENED);
        }

        @Test
        void shouldUseWorkspaceIdFromGateNotContext() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(99L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            Workspace workspace = new Workspace();
            workspace.setId(42L);
            var detect = automaticDetection(workspace, List.of());
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                    .thenReturn(detect);
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            listener.onPullRequestCreated(event);

            var workspaceIdCaptor = ArgumentCaptor.forClass(Long.class);
            verify(agentJobService)
                    .submit(
                            workspaceIdCaptor.capture(),
                            eq(AgentJobType.PULL_REQUEST_REVIEW),
                            any(PullRequestReviewSubmissionRequest.class),
                            any(),
                            any());
            assertThat(workspaceIdCaptor.getValue()).isEqualTo(42L).isNotEqualTo(99L);
        }

        @Test
        void shouldBuildCorrectSubmissionRequest() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("sha256abc", "feature/my-branch", "develop");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            var detect = automaticDetection(workspace, List.of());
            when(practiceReviewDetectionGate.evaluate(eq(pr), any(), any())).thenReturn(detect);
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            listener.onPullRequestCreated(event);

            var captor = ArgumentCaptor.forClass(PullRequestReviewSubmissionRequest.class);
            verify(agentJobService)
                    .submit(eq(WORKSPACE_ID), eq(AgentJobType.PULL_REQUEST_REVIEW), captor.capture(), any(), any());

            PullRequestReviewSubmissionRequest request = captor.getValue();
            assertThat(request.pullRequest()).isSameAs(prData);
            assertThat(request.headRefOid()).isEqualTo("sha256abc");
            assertThat(request.headRefName()).isEqualTo("feature/my-branch");
            assertThat(request.baseRefName()).isEqualTo("develop");
        }

        @Test
        void shouldDelegateDraftFilteringToGate() {
            var prData = createPrData(Issue.State.OPEN, true, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                    .thenReturn(new GateDecision.Skip("draft PR"));

            listener.onPullRequestCreated(event);

            verify(practiceReviewDetectionGate).evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldPassPullRequestCreatedTriggerEventName() {
            PullRequest pr = setupHappyPath();
            var prData = createPrData(Issue.State.OPEN, false, false);

            listener.onPullRequestCreated(new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L)));

            verify(practiceReviewDetectionGate).evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);
        }

        @Test
        void shouldPassPullRequestReadyTriggerEventName() {
            PullRequest pr = setupHappyPath();
            var prData = createPrData(Issue.State.OPEN, false, false);

            listener.onPullRequestReady(new ScmDomainEvent.PullRequestReady(prData, webhookContext(1L)));

            verify(practiceReviewDetectionGate).evaluate(pr, ScmSignals.PULL_REQUEST_READY, TriggerMode.AUTO);
        }

        @Test
        void shouldPassPullRequestSynchronizedTriggerEventName() {
            PullRequest pr = setupHappyPath();
            var prData = createPrData(Issue.State.OPEN, false, false);

            listener.onPullRequestSynchronized(new ScmDomainEvent.PullRequestSynchronized(prData, webhookContext(1L)));

            verify(practiceReviewDetectionGate).evaluate(pr, ScmSignals.PULL_REQUEST_SYNCHRONIZED, TriggerMode.AUTO);
        }

        @Test
        void shouldNotPropagateExceptionsFromSubmit() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                    .thenReturn(automaticDetection(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("DB error"));

            // Swallowing is the contract: this listener runs on the webhook consumer, and a thrown
            // exception would NAK the delivery and redeliver the same doomed submission.
            assertThatCode(() -> listener.onPullRequestCreated(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldNotPropagateExceptionsFromGate() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO))
                    .thenThrow(new RuntimeException("DB connectivity error"));

            // Should not throw — outer catch handles gate exceptions
            listener.onPullRequestCreated(event);

            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }
    }

    @Nested
    class PullRequestSynchronizedTests {

        @Test
        void shouldSubmitWhenGatePasses() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestSynchronized(prData, webhookContext(1L));

            setupHappyPath();

            listener.onPullRequestSynchronized(event);

            assertThat(captureSubmission(WORKSPACE_ID).triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_SYNCHRONIZED);
        }

        @Test
        void shouldRecordSyncDiscoveredSignalsWithoutReviewingThem() {
            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestSynchronized(prData, syncContext());

            listener.onPullRequestSynchronized(event);

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }
    }

    @Nested
    class ReviewSubmittedTests {

        @Test
        void shouldSubmitWhenGatePasses() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            // ReviewSubmitted path calls PullRequestData.from(pr), which needs repository + author
            Repository repo = new Repository();
            repo.setId(100L);
            repo.setNameWithOwner("owner/repo");
            repo.setDefaultBranch("main");
            pr.setRepository(repo);
            pr.setNumber(PR_NUMBER);
            pr.setTitle("Test PR");
            pr.setHtmlUrl("https://github.com/owner/repo/pull/42");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            var detect = automaticDetection(workspace, List.of());
            when(practiceReviewDetectionGate.evaluate(
                            pr, ScmSignals.PULL_REQUEST_REVIEWED, TriggerMode.AUTO, new ReviewSubject(200L, true)))
                    .thenReturn(detect);
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            listener.onReviewSubmitted(event);

            // A ReviewSubmitted event carries no PullRequestData, so the request is rebuilt from the
            // entity via PullRequestData.from(pr). Assert the rebuilt payload, not just the call.
            var request = captureSubmission(WORKSPACE_ID);
            assertThat(request.triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_REVIEWED);
            assertThat(request.pullRequest().number()).isEqualTo(PR_NUMBER);
            assertThat(request.pullRequest().repository().nameWithOwner()).isEqualTo("owner/repo");
            assertThat(request.headRefOid()).isEqualTo("abc123");
            assertThat(request.reviewId()).isEqualTo(100L);
            assertThat(request.aboutUserId()).isEqualTo(200L);
        }

        @Test
        void shouldRecordSyncDiscoveredSignalsWithoutReviewingThem() {
            // Unlike the other handlers, ReviewSubmitted carries no PullRequestData, so the PR must be
            // loaded before anything can be keyed.
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, syncContext());

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            Repository repo = new Repository();
            repo.setId(100L);
            repo.setNameWithOwner("owner/repo");
            repo.setDefaultBranch("main");
            pr.setRepository(repo);
            pr.setNumber(PR_NUMBER);
            pr.setTitle("Test PR");
            pr.setHtmlUrl("https://github.com/owner/repo/pull/42");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onReviewSubmitted(event);

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenPRNotFound() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.empty());

            listener.onReviewSubmitted(event);

            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenPRIsClosed() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = new PullRequest();
            pr.setState(Issue.State.CLOSED);
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onReviewSubmitted(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenPRIsMerged() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = new PullRequest();
            pr.setState(Issue.State.MERGED);
            pr.setMerged(true);
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onReviewSubmitted(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenIsMergedTrueButStateIsOpen() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = new PullRequest();
            pr.setState(Issue.State.OPEN);
            pr.setMerged(true);
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onReviewSubmitted(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenMissingBranchInfo() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = mockPullRequest(null, "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            listener.onReviewSubmitted(event);

            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldSkipWhenGateReturnsSkip() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            lenient()
                    .when(practiceReviewDetectionGate.evaluate(
                            pr, ScmSignals.PULL_REQUEST_REVIEWED, TriggerMode.AUTO, new ReviewSubject(200L, true)))
                    .thenReturn(new GateDecision.Skip("no matching practices"));

            listener.onReviewSubmitted(event);

            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void shouldNotPropagateExceptionsFromSubmit() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            Repository repo = new Repository();
            repo.setId(100L);
            repo.setNameWithOwner("owner/repo");
            repo.setDefaultBranch("main");
            pr.setRepository(repo);
            pr.setNumber(PR_NUMBER);
            pr.setTitle("Test PR");
            pr.setHtmlUrl("https://github.com/owner/repo/pull/42");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(practiceReviewDetectionGate.evaluate(
                            pr, ScmSignals.PULL_REQUEST_REVIEWED, TriggerMode.AUTO, new ReviewSubject(200L, true)))
                    .thenReturn(automaticDetection(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("submission failed"));

            assertThatCode(() -> listener.onReviewSubmitted(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldNotPropagateExceptionsFromGate() {
            var reviewData = createReviewData();
            var event = new ScmDomainEvent.ReviewSubmitted(reviewData, webhookContext(1L));

            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            lenient()
                    .when(practiceReviewDetectionGate.evaluate(
                            pr, ScmSignals.PULL_REQUEST_REVIEWED, TriggerMode.AUTO, new ReviewSubject(200L, true)))
                    .thenThrow(new RuntimeException("unexpected gate error"));

            // Should not throw — outer catch handles gate exceptions
            listener.onReviewSubmitted(event);

            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }
    }

    @Nested
    class RetrospectivePullRequestTests {

        /** A merged PR keeps its branch refs; the retrospective path must NOT short-circuit on merged state. */
        private PullRequest mergedPullRequest() {
            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            pr.setState(Issue.State.MERGED);
            pr.setMerged(true);
            return pr;
        }

        @Test
        void onPullRequestMerged_routesThroughGateAndCarriesTheMergedTriggerOntoTheJob() {
            // The merged terminal state IS the reason this trigger runs — it must reach the gate, unlike the
            // live create/ready/sync handlers that short-circuit on merged.
            PullRequest pr = mergedPullRequest();
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_MERGED, TriggerMode.AUTO))
                    .thenReturn(automaticDetection(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            var prData = createPrData(Issue.State.MERGED, false, true);
            listener.onPullRequestMerged(new ScmDomainEvent.PullRequestMerged(prData, webhookContext(1L)));

            verify(practiceReviewDetectionGate).evaluate(pr, ScmSignals.PULL_REQUEST_MERGED, TriggerMode.AUTO);
            assertThat(captureSubmission(WORKSPACE_ID).triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_MERGED);
        }

        @Test
        void onPullRequestMerged_recordsSyncDiscoveredMergesWithoutReviewingThem() {
            // A sync replays every historical merge, unlike a live merge event; retrospective detection
            // only runs for real-time transitions.
            var prData = createPrData(Issue.State.MERGED, false, true);
            listener.onPullRequestMerged(new ScmDomainEvent.PullRequestMerged(prData, syncContext()));

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void onPullRequestClosed_routesAbandonedCloseThroughGate() {
            PullRequest pr = mockPullRequest("abc123", "feature/test", "main");
            pr.setState(Issue.State.CLOSED);
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            when(practiceReviewDetectionGate.evaluate(pr, ScmSignals.PULL_REQUEST_CLOSED, TriggerMode.AUTO))
                    .thenReturn(automaticDetection(workspace, List.of()));
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            var prData = createPrData(Issue.State.CLOSED, false, false);
            // wasMerged=false → abandoned close, routed under PULL_REQUEST_CLOSED.
            listener.onPullRequestClosed(new ScmDomainEvent.PullRequestClosed(prData, false, webhookContext(1L)));

            verify(practiceReviewDetectionGate).evaluate(pr, ScmSignals.PULL_REQUEST_CLOSED, TriggerMode.AUTO);
            assertThat(captureSubmission(WORKSPACE_ID).triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_CLOSED);
        }

        @Test
        void onPullRequestClosed_doesNotDoubleFireWhenWasMerged() {
            // On a merge the processors publish BOTH PullRequestClosed(wasMerged=true) AND PullRequestMerged.
            // The merge is owned by onPullRequestMerged, so the closed handler must short-circuit before any DB
            // work to avoid submitting a duplicate job for the same landing.
            var prData = createPrData(Issue.State.MERGED, false, true);
            listener.onPullRequestClosed(new ScmDomainEvent.PullRequestClosed(prData, true, webhookContext(1L)));

            verify(signalRecorder, never()).record(any(), any(), any());
            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(practiceReviewDetectionGate, never()).evaluate(any(), any(), any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }

        @Test
        void onPullRequestClosed_recordsSyncDiscoveredClosesWithoutReviewingThem() {
            var prData = createPrData(Issue.State.CLOSED, false, false);
            listener.onPullRequestClosed(new ScmDomainEvent.PullRequestClosed(prData, false, syncContext()));

            verify(signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
            verify(pullRequestRepository, never()).findByIdWithAllForGate(any());
            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }
    }

    @Nested
    class CollaborationTests {

        /**
         * Creates a real gate with mocked leaf dependencies and a listener wired to it.
         * Uses explicit mock creation (not @Mock) to avoid unnecessary stubbing from outer class.
         */
        private record CollaborationFixture(
                AgentJobEventListener listener,
                UserRoleChecker userRoleChecker,
                PracticeReviewReadiness practiceDetectionReadiness,
                PracticeRepository practiceRepository,
                WorkspaceResolver workspaceResolver) {
            static CollaborationFixture create(
                    AgentJobService agentJobService,
                    PullRequestRepository pullRequestRepository,
                    SignalRecorder signalRecorder) {
                var userRoleChecker = mock(UserRoleChecker.class);
                var practiceDetectionReadiness = mock(PracticeReviewReadiness.class);
                var practiceRepository = mock(PracticeRepository.class);
                var workspaceResolver = mock(WorkspaceResolver.class);
                var coverageService = mock(PracticeReviewCoverageService.class);
                lenient()
                        .when(coverageService.admits(
                                any(Workspace.class),
                                nullable(String.class),
                                nullable(String.class),
                                nullable(ReviewSubject.class)))
                        .thenReturn(true);
                var realGate = new PracticeReviewDetectionGate(
                        practiceDetectionReadiness,
                        practiceRepository,
                        workspaceResolver,
                        mock(PracticeSignalOptions.class),
                        coverageService);
                // One resolver for both, as in production: the ledger key and the gate must agree on which
                // workspace owns the repository.
                var listener = new AgentJobEventListener(
                        agentJobService, pullRequestRepository, realGate, workspaceResolver, signalRecorder);
                return new CollaborationFixture(
                        listener, userRoleChecker, practiceDetectionReadiness, practiceRepository, workspaceResolver);
            }
        }

        private PullRequest setupCollaborationPR() {
            PullRequest pr = new PullRequest();
            pr.setId(PR_ID);
            pr.setHeadRefOid("abc123");
            pr.setHeadRefName("feature/test");
            pr.setBaseRefName("main");
            pr.setBaseRefOid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            pr.setState(Issue.State.OPEN);
            pr.setDraft(false);

            Repository repo = new Repository();
            repo.setNameWithOwner("owner/repo");
            pr.setRepository(repo);

            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            return pr;
        }

        @Test
        void listenerWithRealGateSubmitsOnDetectCarryingTheGatesTrigger() {
            var fixture = CollaborationFixture.create(agentJobService, pullRequestRepository, signalRecorder);

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            workspace.setWorkspaceSlug("test-workspace");
            workspace.getFeatures().setPracticesEnabled(true);
            when(fixture.workspaceResolver().resolveForRepository("owner/repo")).thenReturn(Optional.of(workspace));
            when(fixture.practiceDetectionReadiness().hasRunnableAgent(WORKSPACE_ID))
                    .thenReturn(true);

            Practice practice = new Practice();
            practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
            practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
            when(fixture.practiceRepository().findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(practice));

            setupCollaborationPR();
            when(agentJobService.submit(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));
            fixture.listener().onPullRequestCreated(event);

            var request = captureSubmission(WORKSPACE_ID);
            assertThat(request.triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_OPENED);
            assertThat(request.pullRequest().repository().nameWithOwner()).isEqualTo("owner/repo");
        }

        @Test
        void realGateSkipsWhenNoMatchingPractices() {
            var fixture = CollaborationFixture.create(agentJobService, pullRequestRepository, signalRecorder);

            Workspace workspace = new Workspace();
            workspace.setId(WORKSPACE_ID);
            workspace.getFeatures().setPracticesEnabled(true);
            when(fixture.workspaceResolver().resolveForRepository("owner/repo")).thenReturn(Optional.of(workspace));
            when(fixture.practiceDetectionReadiness().hasRunnableAgent(WORKSPACE_ID))
                    .thenReturn(true);

            // Practice only matches ReviewSubmitted, not PullRequestCreated
            Practice practice = new Practice();
            practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_REVIEWED));
            practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
            when(fixture.practiceRepository().findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(practice));

            setupCollaborationPR();

            var prData = createPrData(Issue.State.OPEN, false, false);
            var event = new ScmDomainEvent.PullRequestCreated(prData, webhookContext(1L));
            fixture.listener().onPullRequestCreated(event);

            verify(agentJobService, never()).submit(any(), any(), any(), any());
        }
    }
}
