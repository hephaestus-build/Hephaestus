package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.ExistingSummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class PracticeFeedbackDispatchServiceTest extends BaseUnitTest {

    @Mock
    private FeedbackDispatchRepository repository;

    @Mock
    private PracticeFeedbackDeliveryPolicy policy;

    @Mock
    private SummaryChannel channel;

    @Mock
    private TransactionTemplate transactions;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private DiffNotePoster diffNotePoster;

    private PracticeFeedbackDispatchService service;
    private AgentJob job;
    private FeedbackDispatch dispatch;

    @BeforeEach
    void setUp() {
        var mapper = JsonMapper.builder().build();
        var stateMachine =
                new FeedbackDispatchStateMachine(repository, transactions, new SimpleMeterRegistry(), mapper);
        lenient().when(channel.kind()).thenReturn(IntegrationKind.GITLAB);
        service = new PracticeFeedbackDispatchService(
                repository,
                policy,
                new PullRequestCommentPoster(List.of(channel)),
                transactions,
                mapper,
                feedbackRepository,
                diffNotePoster,
                stateMachine);
        lenient()
                .when(channel.formatPullRequestSubjectId(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "!" + invocation.getArgument(1));
        lenient()
                .when(channel.formatIssueSubjectId(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "#" + invocation.getArgument(1));
        lenient()
                .doAnswer(invocation -> {
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactions)
                .executeWithoutResult(any());
        lenient().when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        Workspace workspace = new Workspace();
        workspace.setId(7L);
        job = reviewJob(workspace);
        dispatch = dispatch(FeedbackDispatchState.PENDING);
        lenient()
                .when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        lenient()
                .when(repository.claim(any(), any(), anyString(), any(), any(Integer.class)))
                .thenReturn(1);
        lenient().when(repository.beginWrite(any(), any(), anyString())).thenReturn(1);
        lenient().when(repository.finish(any())).thenReturn(1);
        lenient()
                .when(diffNotePoster.reconcileInlineNotes(any(), eq(List.of())))
                .thenReturn(new DiffNotePoster.DiffNoteResult(0, 0, List.of()));
        lenient()
                .when(policy.evaluatePullRequest(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.allowed(
                        new de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest()));
    }

    @Test
    void retryingTheSameJobReusesItsProviderMarkerWithoutPostingAgain() {
        dispatch = dispatch(FeedbackDispatchState.UNCERTAIN, true, 1);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), eq(summaryMarker(job))))
                .thenReturn(ExistingSummaryLookup.found(
                        new SummaryHandle("provider-42", "https://github.com/owner/repo/pull/42#issuecomment-987654")));

        PracticeFeedbackDispatchService.Result result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(result.externalRef()).isEqualTo("provider-42");
        assertThat(result.externalUrl()).isEqualTo("https://github.com/owner/repo/pull/42#issuecomment-987654");
        verify(channel, never()).postSummary(any(), any());
        verify(repository)
                .finish(argThat(completion -> completion.state().equals("SENT")
                        && "provider-42".equals(completion.externalRef())
                        && completion.error() == null));
    }

    @Test
    void aLaterReviewJobPostsANewSummaryInsteadOfRewritingThePreviousReview() {
        AgentJob laterJob = reviewJob(job.getWorkspace());
        dispatch = dispatch(job, FeedbackDispatchState.PENDING, false, 0, "first review");
        FeedbackDispatch laterDispatch = dispatch(laterJob, FeedbackDispatchState.PENDING, false, 0, "later review");
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + laterJob.getId(), 7L))
                .thenReturn(Optional.of(laterDispatch));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), eq(new FeedbackContent("first review", summaryMarker(job)))))
                .thenReturn(new SummaryHandle("provider-1"));
        when(channel.postSummary(any(), eq(new FeedbackContent("later review", summaryMarker(laterJob)))))
                .thenReturn(new SummaryHandle("provider-2"));

        var first = dispatchAutomaticReview(job, "first review", Set.of("practice"));
        var later = dispatchAutomaticReview(laterJob, "later review", Set.of("practice"));

        assertThat(first.externalRef()).isEqualTo("provider-1");
        assertThat(later.externalRef()).isEqualTo("provider-2");
        verify(channel).postSummary(any(), eq(new FeedbackContent("first review", summaryMarker(job))));
        verify(channel).postSummary(any(), eq(new FeedbackContent("later review", summaryMarker(laterJob))));
    }

    @Test
    void unknownProviderLookupBecomesUncertainAndNeverPosts() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.unknown());

        PracticeFeedbackDispatchService.Result result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(channel, never()).postSummary(any(), any());
        verify(repository)
                .finish(argThat(completion -> completion.state().equals("UNCERTAIN")
                        && completion.externalRef() == null
                        && completion.error() != null));
    }

    @Test
    void duplicateWakeupReturnsAlreadySentDispatchWithoutClaiming() {
        dispatch = dispatch(FeedbackDispatchState.SENT);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));

        PracticeFeedbackDispatchService.Result result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(result.externalRef()).isEqualTo("provider-42");
        verify(repository, never()).claim(any(), any(), anyString(), any(), any(Integer.class));
        verify(channel, never()).findExistingSummary(any(), any());
    }

    @Test
    void losingAClaimToAnotherWorkerDefersWithoutProviderIo() {
        when(repository.claim(any(), any(), anyString(), any(), any(Integer.class)))
                .thenReturn(0);

        PracticeFeedbackDispatchService.Result result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.IN_PROGRESS);
        verify(channel, never()).findExistingSummary(any(), any());
    }

    @Test
    void claimUsesFullLeaseAndPolicyIsCheckedAfterLookupBeforePosting() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any())).thenReturn(new SummaryHandle("provider-42"));
        Instant before = Instant.now();

        PracticeFeedbackDispatchService.Result result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        ArgumentCaptor<Instant> leaseUntil = ArgumentCaptor.forClass(Instant.class);
        verify(repository).claim(any(), any(), anyString(), leaseUntil.capture(), any(Integer.class));
        assertThat(leaseUntil.getValue()).isAfterOrEqualTo(before.plus(PracticeFeedbackDispatchService.LEASE));
        InOrder order = inOrder(channel, policy, repository);
        order.verify(channel).findExistingSummary(any(), eq(summaryMarker(job)));
        order.verify(policy).evaluatePullRequest(any(), any(), any(), any());
        order.verify(repository).beginWrite(any(), any(), anyString());
        order.verify(channel).postSummary(any(), eq(new FeedbackContent("body", summaryMarker(job))));
        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
    }

    @Test
    void aWriteWhoseAcknowledgementIsLostIsFoundWithoutReposting() {
        FeedbackDispatch recovering = dispatch(FeedbackDispatchState.UNCERTAIN, true, 1);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch))
                .thenReturn(Optional.of(recovering));
        when(channel.findExistingSummary(any(), any()))
                .thenReturn(
                        ExistingSummaryLookup.absent(),
                        ExistingSummaryLookup.found(new SummaryHandle(
                                "provider-42", "https://github.com/owner/repo/pull/42#issuecomment-987654")));
        when(channel.postSummary(any(), any())).thenReturn(new SummaryHandle("provider-42"));
        when(repository.finish(any())).thenReturn(0, 1);

        var interrupted = dispatchAutomaticReview(job, "body", Set.of("practice"));
        var recovered = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(interrupted.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.IN_PROGRESS);
        assertThat(recovered.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(recovered.externalRef()).isEqualTo("provider-42");
        assertThat(recovered.externalUrl()).isEqualTo("https://github.com/owner/repo/pull/42#issuecomment-987654");
        verify(repository)
                .finish(argThat(completion ->
                        "https://github.com/owner/repo/pull/42#issuecomment-987654".equals(completion.externalUrl())));
        verify(channel, times(1)).postSummary(any(), any());
    }

    @Test
    void aFinalAttemptThatReachedTheProviderRemainsUncertain() {
        dispatch = dispatch(FeedbackDispatchState.UNCERTAIN, false, PracticeFeedbackDispatchService.MAX_ATTEMPTS - 1);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any())).thenThrow(new RuntimeException("connection reset"));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.UNCERTAIN.name())));
    }

    @Test
    void recoveredWriteThatIsNotYetVisibleIsNeverPostedAgain() {
        dispatch = dispatch(FeedbackDispatchState.UNCERTAIN, true, PracticeFeedbackDispatchService.MAX_ATTEMPTS);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());

        PracticeFeedbackDispatchService.Result result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).postSummary(any(), any());
    }

    @Test
    void anAlreadySuppressedDispatchReportsTheReasonItStored() {
        dispatch = dispatch(FeedbackDispatchState.SUPPRESSED, FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE);
        verify(repository, never()).claim(any(), any(), anyString(), any(), any(Integer.class));
    }

    @Test
    void aPauseDropsAutomaticFeedbackTerminally() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(policy.evaluatePullRequest(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.suppressed(
                        FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED);
        var completion = ArgumentCaptor.forClass(FeedbackDispatchCompletion.class);
        verify(repository).finish(completion.capture());
        assertThat(completion.getValue().state()).isEqualTo(FeedbackDispatchState.SUPPRESSED.name());
        assertThat(completion.getValue().suppressionReason()).isEqualTo("WORKSPACE_DELIVERY_PAUSED");
        assertThat(completion.getValue().error()).isNull();
        verify(channel, never()).postSummary(any(), any());
    }

    @Test
    void aPauseSuppressesAnApprovedProposalTerminally() {
        var feedback = de.tum.cit.aet.hephaestus.practices.feedback.Feedback.builder()
                .id(UUID.randomUUID())
                .body("approved body")
                .build();
        FeedbackDispatch approved = dispatch(FeedbackDispatchState.PENDING, feedback.getId());
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(approved));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(channel.findExistingSummary(any(), eq(approvedMarker(feedback))))
                .thenReturn(ExistingSummaryLookup.absent());
        when(policy.evaluatePullRequest(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.suppressed(
                        FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        var completion = ArgumentCaptor.forClass(FeedbackDispatchCompletion.class);
        verify(repository).finish(completion.capture());
        assertThat(completion.getValue().state()).isEqualTo(FeedbackDispatchState.SUPPRESSED.name());
        assertThat(completion.getValue().suppressionReason()).isEqualTo("WORKSPACE_DELIVERY_PAUSED");
        verify(channel, never()).postSummary(any(), any());
    }

    @Test
    void oneDispatchLeaseOwnsTheExactApprovedPackage() {
        Feedback feedback = approvedFeedback();
        FeedbackDispatch approved = dispatch(FeedbackDispatchState.PENDING, feedback.getId());
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(approved));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(channel.findExistingSummary(any(), eq(approvedMarker(feedback))))
                .thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(new SummaryHandle("summary-ref"));
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + feedback.getId() + ":0",
                new FeedbackAnchor.DiffAnchor("src/Review.java", 12, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "inline-ref",
                "thread-ref");
        when(diffNotePoster.reconcileApprovedInlineNotes(any(), any(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(1, 0, List.of(signal)));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        verify(repository).claim(any(), any(), anyString(), any(), any(Integer.class));
        verify(diffNotePoster)
                .reconcileApprovedInlineNotes(
                        job,
                        feedback.getId(),
                        List.of(new PracticeDetectionResultParser.DiffNote(
                                "src/Review.java", 12, null, "exact inline", "old-key", null)));
    }

    @Test
    void incompleteApprovedPackageReusesItsSummaryDuringRecovery() {
        Feedback feedback = approvedFeedback();
        FeedbackDispatch initial = approvedDispatch(FeedbackDispatchState.PENDING, feedback.getId(), false, null, 0);
        FeedbackDispatch recovering =
                approvedDispatch(FeedbackDispatchState.UNCERTAIN, feedback.getId(), true, "summary-ref", 1);
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(initial))
                .thenReturn(Optional.of(recovering));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(channel.findExistingSummary(any(), eq(approvedMarker(feedback))))
                .thenReturn(ExistingSummaryLookup.absent(), found("summary-ref"));
        when(channel.postSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(new SummaryHandle("summary-ref"));
        var deliveredSignal = new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + feedback.getId() + ":0",
                new FeedbackAnchor.DiffAnchor("src/Review.java", 12, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "inline-ref",
                "thread-ref");
        when(diffNotePoster.reconcileApprovedInlineNotes(any(), any(), any()))
                .thenReturn(
                        new DiffNotePoster.DiffNoteResult(0, 1, List.of()),
                        new DiffNotePoster.DiffNoteResult(1, 0, List.of(deliveredSignal)));

        var incomplete = service.dispatchApproved(job, feedback);
        var recovered = service.dispatchApproved(job, feedback);

        assertThat(incomplete.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(incomplete.externalRef()).isEqualTo("summary-ref");
        assertThat(recovered.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(recovered.externalRef()).isEqualTo("summary-ref");
        verify(channel, times(1))
                .postSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback))));
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.UNCERTAIN.name())
                        && "summary-ref".equals(completion.externalRef())));
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.SENT.name())
                        && "summary-ref".equals(completion.externalRef())));
    }

    @Test
    void shouldReopenOnlyItsOwnFenceWhenTheChannelProvesNothingWasSent() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any()))
                .thenThrow(new FeedbackNotSentException("GitLab note not sent", new RuntimeException("timeout")));
        when(repository.releaseUnsentWrite(any(), any(), anyString())).thenReturn(1);

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        var began = ArgumentCaptor.forClass(String.class);
        var released = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(repository);
        order.verify(repository).beginWrite(eq(dispatch.getId()), eq(7L), began.capture());
        order.verify(repository).releaseUnsentWrite(eq(dispatch.getId()), eq(7L), released.capture());
        order.verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.UNCERTAIN.name())));
        assertThat(released.getValue()).isEqualTo(began.getValue());
    }

    @Test
    void shouldKeepTheFenceWhenTheCreateOutcomeIsUnknown() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any()))
                .thenThrow(new FeedbackDeliveryException("createNote transport error: timeout"));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(repository).beginWrite(any(), any(), anyString());
        verify(repository, never()).releaseUnsentWrite(any(), any(), anyString());
    }

    @Test
    void shouldPostApprovedIssueFeedbackOnceOnTheIssueWhenTheJobNamesOnlyTheIssue() {
        AgentJob issueJob = reviewJob(job.getWorkspace());
        issueJob.setJobType(AgentJobType.ISSUE_REVIEW);
        issueJob.setMetadata(JsonNodeFactory.instance
                .objectNode()
                .put("repository_full_name", "acme/api")
                .put("issue_number", 5));
        Feedback feedback = Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(7L)
                .body("approved body")
                .proposedPlacements(new ArrayList<>(List.of(ProposedPlacement.summary("approved body"))))
                .build();
        FeedbackDispatch pending = approvedDispatch(FeedbackDispatchState.PENDING, feedback.getId(), false, null, 0);
        FeedbackDispatch sent =
                approvedDispatch(FeedbackDispatchState.SENT, feedback.getId(), true, "gid://gitlab/Note/5", 1);
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(withoutInlineNotes(pending)))
                .thenReturn(Optional.of(withoutInlineNotes(sent)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(policy.evaluateIssue(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.allowed(
                        new de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue()));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any())).thenReturn(new SummaryHandle("gid://gitlab/Note/5"));

        var delivered = service.dispatchApproved(issueJob, feedback);
        var repeated = service.dispatchApproved(issueJob, feedback);

        assertThat(delivered.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(delivered.externalRef()).isEqualTo("gid://gitlab/Note/5");
        assertThat(repeated.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        InOrder order = inOrder(channel, repository);
        order.verify(channel)
                .findExistingSummary(
                        argThat(target -> target.subjectExternalId().equals("acme/api#5")),
                        eq(approvedMarker(feedback)));
        order.verify(repository).beginWrite(any(), any(), anyString());
        order.verify(channel)
                .postSummary(
                        argThat(target -> target.subjectExternalId().equals("acme/api#5")),
                        eq(new FeedbackContent("approved body", approvedMarker(feedback))));
        verify(channel, times(1)).postSummary(any(), any());
        verify(policy, never()).evaluatePullRequest(any(), any(), any(), any());
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.SENT.name())
                        && "gid://gitlab/Note/5".equals(completion.externalRef())));
    }

    @Test
    void shouldRetryWithoutRecordingAWriteWhenTheTargetCannotBeResolved() {
        job.setMetadata(JsonNodeFactory.instance.objectNode().put("repository_full_name", "acme/api"));
        Feedback feedback = approvedFeedback();
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(dispatch(FeedbackDispatchState.PENDING, feedback.getId())));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).findExistingSummary(any(), any());
        verify(channel, never()).postSummary(any(), any());
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.UNCERTAIN.name())
                        && completion.error() != null
                        && completion.error().contains("pr_number")));
    }

    @Test
    void shouldFailATargetThatNeverResolvesInsteadOfHoldingAnUncertainWrite() {
        job.setMetadata(JsonNodeFactory.instance.objectNode().put("repository_full_name", "acme/api"));
        Feedback feedback = approvedFeedback();
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(approvedDispatch(
                        FeedbackDispatchState.UNCERTAIN,
                        feedback.getId(),
                        false,
                        null,
                        PracticeFeedbackDispatchService.MAX_ATTEMPTS - 1)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.FAILED);
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).postSummary(any(), any());
    }

    private FeedbackDispatch dispatch(FeedbackDispatchState state, UUID feedbackId) {
        return approvedDispatch(state, feedbackId, false, null, 0);
    }

    private static FeedbackDispatch withoutInlineNotes(FeedbackDispatch approved) {
        var mapper = JsonMapper.builder().build();
        return new FeedbackDispatch(
                approved.getId(),
                approved.getDestinationKey(),
                approved.getWorkspaceId(),
                approved.getAgentJobId(),
                approved.getFeedbackId(),
                approved.getDestination(),
                approved.getState(),
                approved.getBody(),
                approved.getPracticeSlugs(),
                mapper.valueToTree(new PracticeDetectionResultParser.DeliveryContent(
                        approved.getBody(), List.of(), List.of(), null)),
                approved.getDeliveredPlacements(),
                approved.getWriteStarted(),
                approved.getDeliveredExternalRef(),
                approved.getDeliveredExternalUrl(),
                approved.getLeaseOwner(),
                approved.getLeaseExpiresAt(),
                approved.getNextAttemptAt(),
                approved.getAttemptCount(),
                approved.getSuppressionReason(),
                approved.getLastError(),
                approved.getProjectedAt(),
                approved.getProjectionOwner(),
                approved.getProjectionExpiresAt(),
                approved.getCreatedAt(),
                approved.getUpdatedAt());
    }

    private static Feedback approvedFeedback() {
        return Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(7L)
                .body("approved body")
                .proposedPlacements(new ArrayList<>(List.of(
                        ProposedPlacement.summary("approved body"),
                        ProposedPlacement.inline("exact inline", "src/Review.java", 12, null, "old-key"))))
                .build();
    }

    private FeedbackDispatch approvedDispatch(
            FeedbackDispatchState state,
            UUID feedbackId,
            boolean writeStarted,
            @Nullable String externalRef,
            int attemptCount) {
        FeedbackDispatch base = dispatch(state, writeStarted, attemptCount);
        var mapper = JsonMapper.builder().build();
        return new FeedbackDispatch(
                base.getId(),
                "approved:" + feedbackId,
                base.getWorkspaceId(),
                base.getAgentJobId(),
                feedbackId,
                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                state,
                "approved body",
                base.getPracticeSlugs(),
                mapper.valueToTree(new PracticeDetectionResultParser.DeliveryContent(
                        "approved body",
                        List.of(new PracticeDetectionResultParser.DiffNote(
                                "src/Review.java", 12, null, "exact inline", "old-key", null)),
                        List.of(),
                        null)),
                mapper.valueToTree(List.of()),
                writeStarted,
                externalRef,
                null,
                null,
                null,
                base.getNextAttemptAt(),
                attemptCount,
                null,
                null,
                null,
                null,
                null,
                base.getCreatedAt(),
                base.getUpdatedAt());
    }

    private FeedbackDispatch dispatch(FeedbackDispatchState state, FeedbackSuppressionReason reason) {
        FeedbackDispatch base = dispatch(state, false);
        return new FeedbackDispatch(
                base.getId(),
                base.getDestinationKey(),
                base.getWorkspaceId(),
                base.getAgentJobId(),
                base.getFeedbackId(),
                base.getDestination(),
                base.getState(),
                base.getBody(),
                base.getPracticeSlugs(),
                base.getPackageContent(),
                base.getDeliveredPlacements(),
                base.getWriteStarted(),
                base.getDeliveredExternalRef(),
                null,
                base.getLeaseOwner(),
                base.getLeaseExpiresAt(),
                base.getNextAttemptAt(),
                base.getAttemptCount(),
                reason.name(),
                base.getLastError(),
                base.getProjectedAt(),
                base.getProjectionOwner(),
                base.getProjectionExpiresAt(),
                base.getCreatedAt(),
                base.getUpdatedAt());
    }

    private FeedbackDispatch dispatch(FeedbackDispatchState state) {
        return dispatch(state, false);
    }

    private PracticeFeedbackDispatchService.Result dispatchAutomaticReview(
            AgentJob job, String body, Set<String> practiceSlugs) {
        return service.dispatchAutomaticPackage(
                job,
                new PracticeDetectionResultParser.DeliveryContent(body, List.of(), List.of(), null),
                practiceSlugs);
    }

    private FeedbackDispatch dispatch(FeedbackDispatchState state, boolean writeStarted) {
        return dispatch(state, writeStarted, 0);
    }

    private FeedbackDispatch dispatch(FeedbackDispatchState state, boolean writeStarted, int attemptCount) {
        return dispatch(job, state, writeStarted, attemptCount);
    }

    private FeedbackDispatch dispatch(
            AgentJob targetJob, FeedbackDispatchState state, boolean writeStarted, int attemptCount) {
        return dispatch(targetJob, state, writeStarted, attemptCount, "body");
    }

    private FeedbackDispatch dispatch(
            AgentJob targetJob, FeedbackDispatchState state, boolean writeStarted, int attemptCount, String body) {
        var mapper = JsonMapper.builder().build();
        return new FeedbackDispatch(
                UUID.randomUUID(),
                "review:" + targetJob.getId(),
                7L,
                targetJob.getId(),
                null,
                FeedbackDispatchDestination.AUTOMATIC_REVIEW_PACKAGE,
                state,
                body,
                mapper.valueToTree(List.of("practice")),
                mapper.valueToTree(new PracticeDetectionResultParser.DeliveryContent(body, List.of(), List.of(), null)),
                mapper.valueToTree(List.of()),
                writeStarted,
                state == FeedbackDispatchState.SENT ? "provider-42" : null,
                null,
                null,
                null,
                Instant.now(),
                attemptCount,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static AgentJob reviewJob(Workspace workspace) {
        AgentJob reviewJob = new AgentJob();
        reviewJob.setId(UUID.randomUUID());
        reviewJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        reviewJob.setIntegrationKind(IntegrationKind.GITLAB);
        reviewJob.setMetadata(JsonNodeFactory.instance
                .objectNode()
                .put("repository_full_name", "acme/api")
                .put("pr_number", 42));
        reviewJob.setWorkspace(workspace);
        return reviewJob;
    }

    private static String summaryMarker(AgentJob job) {
        return PullRequestCommentPoster.summaryMarkerFor(job);
    }

    private static String approvedMarker(Feedback feedback) {
        return PullRequestCommentPoster.approvedFeedbackMarker(feedback.getId());
    }

    private static ExistingSummaryLookup found(String ref) {
        return ExistingSummaryLookup.found(new SummaryHandle(ref));
    }
}
