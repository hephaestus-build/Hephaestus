package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.ExistingSummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchInsert;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class PracticeFeedbackDispatchServiceTest extends BaseUnitTest {

    /** The head the review job pinned, and the merge request's head unless a test moves it. */
    private static final String REVIEWED_HEAD = "3f2b9c1d0e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c";

    /** A head pushed after the review read the change. */
    private static final String MOVED_HEAD = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b";

    /** An automatic package's line notes, keyed by their source. */
    private static final List<ReviewResultParser.DiffNote> TWO_LINE_NOTES = List.of(
            new ReviewResultParser.DiffNote("src/App.java", 3, null, "Split this.", "k1", null),
            new ReviewResultParser.DiffNote("src/App.java", 9, null, "Name this.", "k2", null));

    /** An approved proposal's line notes, exactly as its placements record them. */
    private static final List<ReviewResultParser.DiffNote> APPROVED_LINE_NOTES = List.of(
            new ReviewResultParser.DiffNote("src/Review.java", 12, null, "exact inline", "old-key", null),
            new ReviewResultParser.DiffNote("src/Review.java", 20, null, "second inline", "second-key", null));

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

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private PersonDataCopyFence personCopies;

    @Mock
    private PersonProcessingSuppression personSuppression;

    private PracticeFeedbackDispatchService service;
    private AgentJob job;
    private FeedbackDispatch dispatch;

    @BeforeEach
    void setUp() {
        var capture = mock(PersonDataCopyFence.Lease.class);
        var admissionJdbc = mock(JdbcOperations.class);
        lenient().when(personCopies.capture()).thenReturn(capture);
        lenient().when(capture.jdbc()).thenReturn(admissionJdbc);
        lenient()
                .when(admissionJdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
                .thenReturn(true);
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
                stateMachine,
                mock(ObservationInvalidationRepository.class),
                new RepeatedSummaryCheck(observationRepository, feedbackRepository),
                new PracticeFeedbackPersonDataAdmission(personCopies, personSuppression));
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
                .when(repository.claim(any(), any(), anyString(), any(), any(Integer.class), anyInt()))
                .thenReturn(1);
        lenient().when(repository.beginWrite(any(), any(), anyString())).thenReturn(1);
        lenient()
                .when(policy.lockedReviewedRevision(any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CURRENT);
        lenient()
                .when(repository.beginInlineWrite(any(), any(), anyString(), anyString()))
                .thenReturn(1);
        lenient().when(repository.finish(any())).thenReturn(1);
        lenient()
                .when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(REVIEWED_HEAD)));
    }

    @Test
    void shouldSuppressAnEmptyPersistedPackageWithoutClaimingDelivery() {
        dispatch = dispatch(job, FeedbackDispatchState.PENDING, false, 0, "");
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));

        var result = dispatchAutomaticReview(job, "", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.refusal()).isEqualTo(FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
        assertThat(result.landed()).isFalse();
        verify(channel, never()).postSummary(any(), any());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void retryingTheSameJobReusesItsProviderMarkerWithoutPostingAgain() {
        dispatch = dispatch(FeedbackDispatchState.UNCERTAIN, true, 1);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), eq(new FeedbackContent("body", summaryMarker(job)))))
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
    void shouldWithholdANoteThatReadsExactlyAsTheOneLastDeliveredOnTheWork() {
        givenLastDeliveredNote("<!-- hephaestus:practice-review:" + UUID.randomUUID() + " -->\nSix surfaces.\n");
        dispatch = dispatch(job, FeedbackDispatchState.PENDING, false, 0, summaryMarker(job) + "\nSix surfaces.\n");
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());

        var result = dispatchAutomaticReview(job, dispatch.getBody(), Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.REPEATS_DELIVERED_NOTE);
        verify(channel, never()).postSummary(any(), any());
        verify(repository, never()).beginWrite(any(), any(), anyString());
    }

    @Test
    void shouldPostANoteThatSaysSomethingTheLastDeliveredOneDidNot() {
        givenLastDeliveredNote("<!-- hephaestus:practice-review:" + UUID.randomUUID() + " -->\nFive surfaces.\n");
        dispatch = dispatch(job, FeedbackDispatchState.PENDING, false, 0, summaryMarker(job) + "\nSix surfaces.\n");
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any())).thenReturn(new SummaryHandle("provider-7"));

        var result = dispatchAutomaticReview(job, dispatch.getBody(), Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(result.externalRef()).isEqualTo("provider-7");
    }

    @Test
    void shouldLeaveAPackageWithLineNotesToTheirOwnReconciliation() {
        String body = summaryMarker(job) + "\nSix surfaces.\n";
        givenLastDeliveredNote("<!-- hephaestus:practice-review:" + UUID.randomUUID() + " -->\nSix surfaces.\n");
        var lineNotes = List.of(new ReviewResultParser.DiffNote("src/App.java", 3, null, "Split this."));
        dispatch = withPackage(
                dispatch(job, FeedbackDispatchState.PENDING, false, 0, body),
                new ReviewResultParser.DeliveryContent(body, lineNotes, List.of(), null));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), any())).thenReturn(new SummaryHandle("provider-8"));
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), any()))
                .thenReturn(delivered(List.of()));

        service.dispatchAutomaticPackage(
                job, new ReviewResultParser.DeliveryContent(body, lineNotes, List.of(), null), Set.of("practice"));

        verify(channel).postSummary(any(), any());
    }

    @Test
    void shouldStoreTheReceiptUnderThisLeaseBeforeAnInlineRequestAndRefuseItOnceTheLeaseIsGone() {
        var lineNotes = List.of(new ReviewResultParser.DiffNote("src/App.java", 3, null, "Split this.", "k", null));
        dispatch = withPackage(
                dispatch(job, FeedbackDispatchState.PENDING, false, 0, ""),
                new ReviewResultParser.DeliveryContent(null, lineNotes, List.of(), List.of()));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<List<InlineFeedbackChannel.DeliveredSignal>, FeedbackDispatchStateMachine.Reservation>>
                fence = ArgumentCaptor.forClass(Function.class);
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), fence.capture()))
                .thenReturn(delivered(List.of()));

        service.dispatchAutomaticPackage(
                job, new ReviewResultParser.DeliveryContent(null, lineNotes, List.of(), List.of()), Set.of("practice"));
        var attempted = InlineFeedbackChannel.DeliveredSignal.attempted(
                "k", FeedbackAnchor.DiffAnchor.singleLine("src/App.java", 3));

        assertThat(fence.getValue().apply(List.of(attempted)))
                .isEqualTo(FeedbackDispatchStateMachine.Reservation.RESERVED);
        verify(repository)
                .beginInlineWrite(
                        eq(dispatch.getId()),
                        eq(7L),
                        anyString(),
                        argThat(placements -> placements.contains("\"writeMayHaveStarted\":true")));
        when(repository.beginInlineWrite(any(), any(), anyString(), anyString()))
                .thenReturn(0);
        assertThat(fence.getValue().apply(List.of(attempted)))
                .isEqualTo(FeedbackDispatchStateMachine.Reservation.LEASE_LOST);
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackSuppressionReason.class,
            names = {
                "ARTIFACT_CLOSED",
                "ARTIFACT_MERGED",
                "WORKSPACE_DELIVERY_PAUSED",
                "RECIPIENT_OPTED_OUT",
                "PUBLIC_SUBJECT_INELIGIBLE"
            })
    void shouldKeepTheExactPolicyRefusalAfterTheFinalSummaryLock(FeedbackSuppressionReason refusal) {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        var finalCheck = new AtomicBoolean();
        when(policy.lockedReviewedRevision(any(), any())).thenAnswer(invocation -> {
            finalCheck.set(true);
            return PracticeFeedbackDeliveryPolicy.ReviewedRevision.CURRENT;
        });
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> finalCheck.get()
                        ? PracticeFeedbackDeliveryPolicy.Decision.suppressed(refusal)
                        : PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(REVIEWED_HEAD)));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(refusal);
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).postSummary(any(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackSuppressionReason.class,
            names = {"ARTIFACT_CLOSED", "WORKSPACE_DELIVERY_PAUSED", "RECIPIENT_OPTED_OUT", "PUBLIC_SUBJECT_INELIGIBLE"
            })
    void shouldKeepTheExactPolicyRefusalAfterTheFinalApprovedSummaryLock(FeedbackSuppressionReason refusal) {
        Feedback feedback = approvedFeedback();
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(
                        Optional.of(approvedDispatch(FeedbackDispatchState.PENDING, feedback.getId(), false, null, 0)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        var finalCheck = new AtomicBoolean();
        when(policy.lockedReviewedRevision(any(), any())).thenAnswer(invocation -> {
            finalCheck.set(true);
            return PracticeFeedbackDeliveryPolicy.ReviewedRevision.CURRENT;
        });
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> finalCheck.get()
                        ? PracticeFeedbackDeliveryPolicy.Decision.suppressed(refusal)
                        : PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(REVIEWED_HEAD)));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(refusal);
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).postSummary(any(), any());
    }

    /** The early read saw the reviewed work; the final check under the work's lock is what decides. */
    @ParameterizedTest
    @EnumSource(
            value = PracticeFeedbackDeliveryPolicy.ReviewedRevision.class,
            names = {"CHANGED", "UNKNOWN"})
    void shouldReserveNoInlineWriteWhenTheLockedCheckRefusesWhatTheEarlyReadAllowed(
            PracticeFeedbackDeliveryPolicy.ReviewedRevision locked) {
        var lineNotes = List.of(new ReviewResultParser.DiffNote("src/App.java", 3, null, "Split this.", "k", null));
        dispatch = withPackage(
                dispatch(job, FeedbackDispatchState.PENDING, false, 0, ""),
                new ReviewResultParser.DeliveryContent(null, lineNotes, List.of(), List.of()));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(policy.lockedReviewedRevision(any(), any())).thenReturn(locked);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<List<InlineFeedbackChannel.DeliveredSignal>, FeedbackDispatchStateMachine.Reservation>>
                fence = ArgumentCaptor.forClass(Function.class);
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), fence.capture()))
                .thenReturn(delivered(List.of()));

        service.dispatchAutomaticPackage(
                job, new ReviewResultParser.DeliveryContent(null, lineNotes, List.of(), List.of()), Set.of("practice"));

        assertThat(fence.getValue()
                        .apply(List.of(InlineFeedbackChannel.DeliveredSignal.attempted(
                                "k", FeedbackAnchor.DiffAnchor.singleLine("src/App.java", 3)))))
                .isEqualTo(
                        locked == PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED
                                ? FeedbackDispatchStateMachine.Reservation.STALE
                                : FeedbackDispatchStateMachine.Reservation.UNKNOWN);
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void shouldKeepAnUnconfirmedInlineWriteUncertainOnTheLastAttemptButFailOneProvablyNeverRequested() {
        var lineNotes = List.of(new ReviewResultParser.DiffNote("src/App.java", 3, null, "Split this.", "k", null));
        dispatch = withPackage(
                dispatch(
                        job,
                        FeedbackDispatchState.UNCERTAIN,
                        false,
                        PracticeFeedbackDispatchService.MAX_ATTEMPTS - 1,
                        ""),
                new ReviewResultParser.DeliveryContent(null, lineNotes, List.of(), List.of()));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new DiffNotePoster.DiffNoteResult(List.of(), false, true, false, false, false, List.of(), null),
                        new DiffNotePoster.DiffNoteResult(
                                List.of(), false, false, false, false, false, List.of(), null));
        var content = new ReviewResultParser.DeliveryContent(null, lineNotes, List.of(), List.of());

        var unconfirmed = service.dispatchAutomaticPackage(job, content, Set.of("practice"));
        var neverRequested = service.dispatchAutomaticPackage(job, content, Set.of("practice"));

        assertThat(unconfirmed.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(neverRequested.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.FAILED);
    }

    /** The last note delivered on this review's work to its developer, as the ledger stored it. */
    private void givenLastDeliveredNote(String storedBody) {
        Observation observation = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(job.getId())
                .aboutUserId(11L)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .build();
        lenient().when(observationRepository.findByAgentJobId(job.getId(), 7L)).thenReturn(List.of(observation));
        lenient()
                .when(feedbackRepository.findLatestDeliveredNote(7L, 11L, "scm.pull_request", 42L))
                .thenReturn(Optional.of(storedBody));
    }

    private static FeedbackAnchor.DiffAnchor anchorOf(ReviewResultParser.DiffNote note) {
        return FeedbackAnchor.DiffAnchor.singleLine(note.filePath(), note.startLine());
    }

    /** The dispatch once an earlier attempt began its inline writes and stored {@code placements}. */
    private static FeedbackDispatch inlineWriteBegun(FeedbackDispatch base, JsonNode placements) {
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
                placements,
                base.getWriteStarted(),
                base.getWriteStartedAt(),
                true,
                base.getDeliveredExternalRef(),
                base.getDeliveredExternalUrl(),
                base.getLeaseOwner(),
                base.getLeaseExpiresAt(),
                base.getNextAttemptAt(),
                base.getAttemptCount(),
                base.getSuppressionReason(),
                base.getLastError(),
                base.getProjectedAt(),
                base.getProjectionOwner(),
                base.getProjectionExpiresAt(),
                base.getCreatedAt(),
                base.getUpdatedAt());
    }

    private static FeedbackDispatch withPackage(FeedbackDispatch base, ReviewResultParser.DeliveryContent content) {
        var mapper = JsonMapper.builder().build();
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
                mapper.valueToTree(content),
                base.getDeliveredPlacements(),
                base.getWriteStarted(),
                base.getWriteStartedAt(),
                base.getInlineWriteStarted(),
                base.getDeliveredExternalRef(),
                base.getDeliveredExternalUrl(),
                base.getLeaseOwner(),
                base.getLeaseExpiresAt(),
                base.getNextAttemptAt(),
                base.getAttemptCount(),
                base.getSuppressionReason(),
                base.getLastError(),
                base.getProjectedAt(),
                base.getProjectionOwner(),
                base.getProjectionExpiresAt(),
                base.getCreatedAt(),
                base.getUpdatedAt());
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
        verify(repository, never()).claim(any(), any(), anyString(), any(), any(Integer.class), anyInt());
        verify(channel, never()).findExistingSummary(any(), any());
    }

    @Test
    void losingAClaimToAnotherWorkerDefersWithoutProviderIo() {
        when(repository.claim(any(), any(), anyString(), any(), any(Integer.class), anyInt()))
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
        verify(repository).claim(any(), any(), anyString(), leaseUntil.capture(), any(Integer.class), anyInt());
        assertThat(leaseUntil.getValue()).isAfterOrEqualTo(before.plus(PracticeFeedbackDispatchService.LEASE));
        InOrder order = inOrder(channel, policy, repository);
        order.verify(channel).findExistingSummary(any(), eq(new FeedbackContent("body", summaryMarker(job))));
        order.verify(policy).evaluateAtEgress(any(), any(), any(), any());
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
    void anInlineOnlyPackageClaimedPastTheBudgetIsSentWhenEveryNoteIsAcknowledged() {
        when(diffNotePoster.findUnacknowledged(eq(job), any(), any(), any()))
                .thenReturn(new DiffNotePoster.InlineLookup(List.of(), true, false));

        var result = service.recover(pastBudgetInlineOnly("POSTED"), job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void anInlineOnlyPackageClaimedPastTheBudgetFailsWhenItsMissingNoteWasNeverRequested() {
        when(diffNotePoster.findUnacknowledged(eq(job), any(), any(), any()))
                .thenReturn(new DiffNotePoster.InlineLookup(List.of(), false, false));

        var result = service.recover(pastBudgetInlineOnly("FAILED"), job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.FAILED);
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void anInlineOnlyPackageClaimedPastTheBudgetKeepsLookingWhileAMissingNoteMayStillLand() {
        when(diffNotePoster.findUnacknowledged(eq(job), any(), any(), any()))
                .thenReturn(new DiffNotePoster.InlineLookup(List.of(), false, true));

        var result = service.recover(pastBudgetInlineOnly("FAILED"), job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAlreadySuppressedDispatchReportsTheReasonItStored() {
        dispatch = dispatch(FeedbackDispatchState.SUPPRESSED, FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE);
        verify(repository, never()).claim(any(), any(), anyString(), any(), any(Integer.class), anyInt());
    }

    @Test
    void aPauseDropsAutomaticFeedbackTerminally() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
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
        Feedback feedback = approvedFeedback();
        FeedbackDispatch approved = dispatch(FeedbackDispatchState.PENDING, feedback.getId());
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(approved));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(channel.findExistingSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(ExistingSummaryLookup.absent());
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
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
        when(channel.findExistingSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(ExistingSummaryLookup.absent());
        when(channel.postSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(new SummaryHandle("summary-ref"));
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + feedback.getId() + ":0",
                new FeedbackAnchor.DiffAnchor("src/Review.java", 12, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "inline-ref",
                "thread-ref");
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), any()))
                .thenReturn(delivered(List.of(signal)));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        verify(repository).claim(any(), any(), anyString(), any(), any(Integer.class), anyInt());
        verify(diffNotePoster)
                .deliverPackage(
                        eq(job),
                        argThat(scope -> feedback.getId().equals(scope.approvedFeedbackId())
                                && scope.marker().equals(InlinePackageScope.approvedMarker(feedback.getId()))),
                        eq(List.of(new ReviewResultParser.DiffNote(
                                "src/Review.java", 12, null, "exact inline", "old-key", null))),
                        any(),
                        any(),
                        any());
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
        when(channel.findExistingSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(ExistingSummaryLookup.absent(), found("summary-ref"));
        when(channel.postSummary(any(), eq(new FeedbackContent("approved body", approvedMarker(feedback)))))
                .thenReturn(new SummaryHandle("summary-ref"));
        var deliveredSignal = new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + feedback.getId() + ":0",
                new FeedbackAnchor.DiffAnchor("src/Review.java", 12, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "inline-ref",
                "thread-ref");
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new DiffNotePoster.DiffNoteResult(
                                List.of(), false, false, false, false, false, List.of(), null),
                        delivered(List.of(deliveredSignal)));

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
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(new Issue()));
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
                        eq(new FeedbackContent("approved body", approvedMarker(feedback))));
        order.verify(repository).beginWrite(any(), any(), anyString());
        order.verify(channel)
                .postSummary(
                        argThat(target -> target.subjectExternalId().equals("acme/api#5")),
                        eq(new FeedbackContent("approved body", approvedMarker(feedback))));
        verify(channel, times(1)).postSummary(any(), any());
        verify(policy).evaluateAtEgress(eq(issueJob), any(), any(), any());
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

    @Test
    void shouldPostApprovedLineNotesAloneWithoutLookingUpOrInventingASummary() {
        Feedback feedback = lineNotesOnlyFeedback(null);
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(lineNotesOnlyDispatch(feedback.getId(), FeedbackDispatchState.PENDING, 0)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        var first = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.POSTED);
        var second = lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.POSTED);
        when(diffNotePoster.deliverPackage(eq(job), any(), eq(APPROVED_LINE_NOTES), any(), any(), any()))
                .thenReturn(delivered(List.of(first, second)));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(result.externalRef()).isNull();
        assertThat(result.deliveredSignals()).containsExactly(first, second);
        verify(channel, never()).findExistingSummary(any(), any());
        verify(channel, never()).postSummary(any(), any());
        verify(repository, never()).beginWrite(any(), any(), anyString());
        var insert = ArgumentCaptor.forClass(FeedbackDispatchInsert.class);
        verify(repository).insertIfAbsent(insert.capture());
        assertThat(insert.getValue().body()).isEmpty();
        assertThat(insert.getValue().packageContent())
                .contains("exact inline", "second inline")
                .contains("\"inlineMarker\":\"" + InlinePackageScope.approvedMarker(feedback.getId()) + "\"");
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.SENT.name())
                        && completion.externalRef() == null
                        && completion.deliveredPlacements().contains("inline-ref-0")
                        && completion.deliveredPlacements().contains("inline-ref-1")));
    }

    @Test
    void shouldKeepApprovedLineNotesThatLandedAcrossARetryAndFinishWithoutASummary() {
        Feedback feedback = lineNotesOnlyFeedback(null);
        var landed = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.POSTED);
        var refused = lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.FAILED);
        var retried = lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.POSTED);
        FeedbackDispatch recovering = lineNotesOnlyDispatch(
                feedback.getId(), FeedbackDispatchState.UNCERTAIN, 1, true, storedPlacements(landed, refused));
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(lineNotesOnlyDispatch(feedback.getId(), FeedbackDispatchState.PENDING, 0)))
                .thenReturn(Optional.of(recovering));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(diffNotePoster.deliverPackage(eq(job), any(), eq(APPROVED_LINE_NOTES), any(), any(), any()))
                .thenReturn(
                        new DiffNotePoster.DiffNoteResult(
                                List.of(landed, refused), false, false, false, false, false, List.of(), null),
                        delivered(List.of(landed, retried)));

        var incomplete = service.dispatchApproved(job, feedback);
        var recovered = service.dispatchApproved(job, feedback);

        assertThat(incomplete.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(incomplete.externalRef()).isNull();
        assertThat(incomplete.landed()).isTrue();
        assertThat(incomplete.deliveredSignals())
                .extracting(
                        InlineFeedbackChannel.DeliveredSignal::deliveryKey,
                        InlineFeedbackChannel.DeliveredSignal::disposition)
                .containsExactlyInAnyOrder(
                        tuple(landed.deliveryKey(), InlineFeedbackChannel.Disposition.POSTED),
                        tuple(refused.deliveryKey(), InlineFeedbackChannel.Disposition.FAILED));
        assertThat(recovered.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(recovered.externalRef()).isNull();
        assertThat(recovered.deliveredSignals())
                .extracting(
                        InlineFeedbackChannel.DeliveredSignal::deliveryKey,
                        InlineFeedbackChannel.DeliveredSignal::disposition)
                .containsExactlyInAnyOrder(
                        tuple(landed.deliveryKey(), InlineFeedbackChannel.Disposition.POSTED),
                        tuple(retried.deliveryKey(), InlineFeedbackChannel.Disposition.POSTED));
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.UNCERTAIN.name())
                        && completion.externalRef() == null
                        && completion.deliveredPlacements().contains("inline-ref-0")));
        verify(channel, never()).findExistingSummary(any(), any());
        verify(channel, never()).postSummary(any(), any());
    }

    @Test
    void missingApprovedProposalKeepsAnUnknownInlineWriteRecoverablePastItsBudget() {
        Feedback feedback = lineNotesOnlyFeedback("reviewed-revision");
        var first = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.FAILED);
        var unknown = InlineFeedbackChannel.DeliveredSignal.attempted(first.deliveryKey(), first.anchor());
        var landed = lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.POSTED);
        var dispatch = lineNotesOnlyDispatch(
                feedback.getId(),
                FeedbackDispatchState.UNCERTAIN,
                PracticeFeedbackDispatchService.MAX_ATTEMPTS - 1,
                true,
                storedPlacements(unknown, landed));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.empty());

        var result = service.recover(dispatch, job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(result.deliveredSignals())
                .anySatisfy(signal -> {
                    assertThat(signal.deliveryKey()).isEqualTo(unknown.deliveryKey());
                    assertThat(signal.unconfirmed()).isTrue();
                })
                .anySatisfy(signal -> {
                    assertThat(signal.externalRef()).isEqualTo(landed.externalRef());
                    assertThat(signal.acknowledged()).isTrue();
                });
        verify(repository).finish(argThat(completion -> completion.nextAttemptAt() != null));
        verify(channel, never()).postSummary(any(), any());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingApprovedProposalExhaustsItsBudgetWhenEveryInlineNoteWasProvenNotCreated() {
        Feedback feedback = lineNotesOnlyFeedback("reviewed-revision");
        var first = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.FAILED);
        var second = lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.FAILED);
        var dispatch = lineNotesOnlyDispatch(
                feedback.getId(),
                FeedbackDispatchState.UNCERTAIN,
                PracticeFeedbackDispatchService.MAX_ATTEMPTS - 1,
                false,
                storedPlacements(
                        InlineFeedbackChannel.DeliveredSignal.notSent(first.deliveryKey(), first.anchor()),
                        InlineFeedbackChannel.DeliveredSignal.notSent(second.deliveryKey(), second.anchor())));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.empty());

        var result = service.recover(dispatch, job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.FAILED);
        assertThat(result.deliveredSignals()).allSatisfy(signal -> {
            assertThat(signal.acknowledged()).isFalse();
            assertThat(signal.unconfirmed()).isFalse();
        });
        verify(channel, never()).postSummary(any(), any());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
        "false,missing,false",
        "false,unknown,false",
        "false,found,false",
        "true,missing,false",
        "true,unknown,false",
        "true,found,false",
        "false,unknown,true",
        "false,found,true",
        "true,unknown,true",
        "true,found,true"
    })
    void shouldReconcileStartedInlineCopiesBeforeWithholdingPubliclyIneligibleSupport(
            boolean approved, String lookup, boolean pastBudget) {
        int attempts = pastBudget ? PracticeFeedbackDispatchService.MAX_ATTEMPTS : 1;
        Feedback feedback = lineNotesOnlyFeedback("proposal-revision");
        var firstKey = approved ? "approved:" + feedback.getId() + ":0" : "old-key";
        var secondKey = approved ? "approved:" + feedback.getId() + ":1" : "second-key";
        var landed = new InlineFeedbackChannel.DeliveredSignal(
                firstKey,
                anchorOf(APPROVED_LINE_NOTES.getFirst()),
                InlineFeedbackChannel.Disposition.POSTED,
                "inline-ref-0",
                null);
        var unknown = InlineFeedbackChannel.DeliveredSignal.attempted(secondKey, anchorOf(APPROVED_LINE_NOTES.get(1)));
        FeedbackDispatch recovering;
        if (approved) {
            when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L))
                    .thenReturn(Optional.of(feedback));
            recovering = lineNotesOnlyDispatch(
                    feedback.getId(),
                    FeedbackDispatchState.UNCERTAIN,
                    attempts,
                    true,
                    storedPlacements(landed, unknown));
        } else {
            recovering = inlineWriteBegun(
                    withPackage(
                            dispatch(job, FeedbackDispatchState.UNCERTAIN, false, attempts, ""),
                            new ReviewResultParser.DeliveryContent(null, APPROVED_LINE_NOTES, List.of(), null)),
                    storedPlacements(landed, unknown));
        }
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.suppressed(
                        FeedbackSuppressionReason.PUBLIC_SUBJECT_INELIGIBLE));
        boolean found = lookup.equals("found");
        var confirmed = new InlineFeedbackChannel.DeliveredSignal(
                secondKey,
                unknown.anchor(),
                InlineFeedbackChannel.Disposition.PRESERVED_EXISTING,
                "inline-ref-1",
                null);
        var inlineChannel = mock(InlineFeedbackChannel.class);
        when(inlineChannel.kind()).thenReturn(IntegrationKind.GITLAB);
        when(inlineChannel.findPosted(any(), any(), any()))
                .thenReturn(found ? List.of(confirmed) : lookup.equals("unknown") ? null : List.of());
        var formatter = mock(PracticeFeedbackCommentFormatter.class);
        lenient()
                .when(formatter.appendInlineFeedbackPrompt(anyString(), eq(job)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var nativePoster =
                new DiffNotePoster(new PullRequestCommentPoster(List.of(channel)), formatter, List.of(inlineChannel));
        when(diffNotePoster.findUnacknowledged(eq(job), any(), eq(APPROVED_LINE_NOTES), any()))
                .thenAnswer(invocation -> nativePoster.findUnacknowledged(
                        job, invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));

        var result = service.recover(recovering, job);

        assertThat(result.status())
                .isEqualTo(
                        found
                                ? PracticeFeedbackDispatchService.Result.Status.SUPPRESSED
                                : PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(result.deliveredSignals())
                .anySatisfy(signal -> assertThat(signal.externalRef()).isEqualTo(landed.externalRef()));
        if (found) {
            assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.PUBLIC_SUBJECT_INELIGIBLE);
            assertThat(result.deliveredSignals())
                    .anySatisfy(signal -> assertThat(signal.externalRef()).isEqualTo(confirmed.externalRef()));
        }
        verify(inlineChannel).findPosted(any(), any(), any());
        verify(inlineChannel, never()).postImmutablePackage(any(), any(), any(), any());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        verify(channel, never()).postSummary(any(), any());
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void shouldSendApprovedLineNotesClaimedPastTheBudgetOnceEveryNoteIsFound() {
        Feedback feedback = lineNotesOnlyFeedback("proposal-revision");
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        var landed = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.POSTED);
        var found = lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.POSTED);
        when(diffNotePoster.findUnacknowledged(
                        eq(job),
                        argThat(scope -> feedback.getId().equals(scope.approvedFeedbackId())
                                && "proposal-revision".equals(scope.reviewedRevision())),
                        eq(APPROVED_LINE_NOTES),
                        any()))
                .thenReturn(new DiffNotePoster.InlineLookup(List.of(found), true, false));

        var result = service.recover(
                lineNotesOnlyDispatch(
                        feedback.getId(),
                        FeedbackDispatchState.UNCERTAIN,
                        PracticeFeedbackDispatchService.MAX_ATTEMPTS,
                        true,
                        storedPlacements(landed)),
                job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(result.externalRef()).isNull();
        assertThat(result.deliveredSignals())
                .extracting(InlineFeedbackChannel.DeliveredSignal::deliveryKey)
                .containsExactlyInAnyOrder(landed.deliveryKey(), found.deliveryKey());
        verify(channel, never()).findExistingSummary(any(), any());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void shouldFailApprovedLineNotesClaimedPastTheBudgetWhileANoteCannotBeFound() {
        Feedback feedback = lineNotesOnlyFeedback("proposal-revision");
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        var landed = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.POSTED);
        when(diffNotePoster.findUnacknowledged(eq(job), any(), eq(APPROVED_LINE_NOTES), any()))
                .thenReturn(new DiffNotePoster.InlineLookup(List.of(), false, false));

        var result = service.recover(
                lineNotesOnlyDispatch(
                        feedback.getId(),
                        FeedbackDispatchState.UNCERTAIN,
                        PracticeFeedbackDispatchService.MAX_ATTEMPTS,
                        true,
                        storedPlacements(landed)),
                job);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.FAILED);
        assertThat(result.externalRef()).isNull();
        assertThat(result.deliveredSignals())
                .extracting(InlineFeedbackChannel.DeliveredSignal::deliveryKey)
                .containsExactly(landed.deliveryKey());
        verify(channel, never()).findExistingSummary(any(), any());
        verify(channel, never()).postSummary(any(), any());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotWriteApprovedLineNotesOntoAHeadPushedAfterTheReviewedPin() {
        Feedback feedback = lineNotesOnlyFeedback(null);
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(lineNotesOnlyDispatch(feedback.getId(), FeedbackDispatchState.PENDING, 0)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(
                        pullRequestAt("9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b")));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.APPROVAL_STALE);
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotWriteAnApprovedPackageWhenNeitherTheProposalNorTheJobPinsAHead() {
        job.setMetadata(JsonNodeFactory.instance
                .objectNode()
                .put("repository_full_name", "acme/api")
                .put("pr_number", 42));
        Feedback feedback = lineNotesOnlyFeedback(null);
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(lineNotesOnlyDispatch(feedback.getId(), FeedbackDispatchState.PENDING, 0)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(result.suppressionReason()).isNull();
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldPostApprovedLineNotesOnTheHeadTheProposalRecordedRatherThanTheJobPin() {
        String recorded = "1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d";
        Feedback feedback = lineNotesOnlyFeedback(recorded);
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(lineNotesOnlyDispatch(feedback.getId(), FeedbackDispatchState.PENDING, 0)));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(recorded)));
        when(diffNotePoster.deliverPackage(eq(job), any(), eq(APPROVED_LINE_NOTES), any(), any(), any()))
                .thenReturn(delivered(List.of(
                        lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.POSTED),
                        lineSignal(feedback, 1, InlineFeedbackChannel.Disposition.POSTED))));

        var result = service.dispatchApproved(job, feedback);

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
    }

    @Test
    void shouldNotPostTheSummaryOnceTheChangeMovedPastTheReviewedCommit() {
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(MOVED_HEAD)));

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED);
        assertThat(result.landed()).isFalse();
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).postSummary(any(), any());
    }

    @Test
    void shouldRetryTheSummaryWithoutClaimingAChangeWhenTheJobPinnedNoReviewedCommit() {
        job.setMetadata(JsonNodeFactory.instance
                .objectNode()
                .put("repository_full_name", "acme/api")
                .put("pr_number", 42));
        when(channel.findExistingSummary(any(), any())).thenReturn(ExistingSummaryLookup.absent());

        var result = dispatchAutomaticReview(job, "body", Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(result.suppressionReason()).isNull();
        verify(repository, never()).beginWrite(any(), any(), anyString());
        verify(channel, never()).postSummary(any(), any());
    }

    @Test
    void shouldRetryLineNotesWithoutClaimingAChangeWhileTheCurrentHeadIsUnknown() {
        var content = new ReviewResultParser.DeliveryContent(null, TWO_LINE_NOTES, List.of(), List.of());
        dispatch = withPackage(dispatch(job, FeedbackDispatchState.PENDING, false, 0, ""), content);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(new PullRequest()));

        var result = service.dispatchAutomaticPackage(job, content, Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        assertThat(result.suppressionReason()).isNull();
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void shouldRefuseLineNotesOnAMovedHeadBeforeAnyProviderCallWhileNoInlineWriteBegan() {
        var content = new ReviewResultParser.DeliveryContent(null, TWO_LINE_NOTES, List.of(), List.of());
        dispatch = withPackage(dispatch(job, FeedbackDispatchState.PENDING, false, 0, ""), content);
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(MOVED_HEAD)));

        var result = service.dispatchAutomaticPackage(job, content, Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED);
        verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        verify(repository, never()).beginInlineWrite(any(), any(), anyString(), anyString());
    }

    @Test
    void shouldKeepALineNoteFoundAfterTheHeadMovedAndRefuseOnlyTheNotesNotYetPosted() {
        var content = new ReviewResultParser.DeliveryContent(null, TWO_LINE_NOTES, List.of(), List.of());
        var unknown = InlineFeedbackChannel.DeliveredSignal.attempted("k1", anchorOf(TWO_LINE_NOTES.get(0)));
        dispatch = inlineWriteBegun(
                withPackage(dispatch(job, FeedbackDispatchState.UNCERTAIN, false, 1, ""), content),
                storedPlacements(unknown));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(MOVED_HEAD)));
        when(policy.currentReviewedRevision(job, null))
                .thenReturn(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED);
        var found = new InlineFeedbackChannel.DeliveredSignal(
                "k1",
                anchorOf(TWO_LINE_NOTES.get(0)),
                InlineFeedbackChannel.Disposition.PRESERVED_EXISTING,
                "gid://gitlab/Note/71",
                null,
                "https://gitlab.example/acme/api/-/merge_requests/42#note_71",
                null,
                InlineFeedbackChannel.Placement.LOCATION_COMMENT);
        var refused = InlineFeedbackChannel.DeliveredSignal.notSent("k2", anchorOf(TWO_LINE_NOTES.get(1)));
        var reviewedRevision = revisionCaptor();
        when(diffNotePoster.deliverPackage(
                        eq(job), any(), eq(TWO_LINE_NOTES), any(), reviewedRevision.capture(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(
                        List.of(found, refused), false, false, false, true, false, List.of(), null));

        var result = service.dispatchAutomaticPackage(job, content, Set.of("practice"));

        assertThat(reviewedRevision.getValue().get())
                .isEqualTo(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED);
        verify(policy).currentReviewedRevision(job, null);
        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.REVIEWED_REVISION_CHANGED);
        assertThat(result.landed()).isTrue();
        verify(repository)
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.SUPPRESSED.name())
                        && completion.deliveredPlacements().contains("gid://gitlab/Note/71")
                        && completion.deliveredPlacements().contains("\"placement\":\"LOCATION_COMMENT\"")));
    }

    @Test
    void shouldKeepLookingForAnUnconfirmedLineNoteRatherThanSettleAMovedHead() {
        var content = new ReviewResultParser.DeliveryContent(null, TWO_LINE_NOTES, List.of(), List.of());
        var unknown = InlineFeedbackChannel.DeliveredSignal.attempted("k1", anchorOf(TWO_LINE_NOTES.get(0)));
        var refused = InlineFeedbackChannel.DeliveredSignal.notSent("k2", anchorOf(TWO_LINE_NOTES.get(1)));
        dispatch = inlineWriteBegun(
                withPackage(dispatch(job, FeedbackDispatchState.UNCERTAIN, false, 1, ""), content),
                storedPlacements(unknown));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));
        when(diffNotePoster.deliverPackage(eq(job), any(), eq(TWO_LINE_NOTES), any(), any(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(
                        List.of(unknown, refused), false, true, false, true, false, List.of(), null));

        var result = service.dispatchAutomaticPackage(job, content, Set.of("practice"));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        verify(repository, never())
                .finish(argThat(completion -> completion.state().equals(FeedbackDispatchState.SUPPRESSED.name())));
    }

    @Test
    void shouldReturnTheStoredPlacementOfASettledPackageAndLeaveAnUnrecordedOneUnknown() {
        var content = new ReviewResultParser.DeliveryContent(null, TWO_LINE_NOTES, List.of(), List.of());
        var located = new InlineFeedbackChannel.DeliveredSignal(
                "k1",
                anchorOf(TWO_LINE_NOTES.get(0)),
                InlineFeedbackChannel.Disposition.POSTED,
                "gid://gitlab/Note/1",
                null,
                null,
                true,
                InlineFeedbackChannel.Placement.LOCATION_COMMENT);
        var unrecorded = new InlineFeedbackChannel.DeliveredSignal(
                "k2",
                anchorOf(TWO_LINE_NOTES.get(1)),
                InlineFeedbackChannel.Disposition.PRESERVED_EXISTING,
                "gid://gitlab/Note/2",
                null);
        dispatch = inlineWriteBegun(
                withPackage(dispatch(job, FeedbackDispatchState.SENT, false, 1, ""), content),
                storedPlacements(located, unrecorded));
        when(repository.findByDestinationKeyAndWorkspaceId("review:" + job.getId(), 7L))
                .thenReturn(Optional.of(dispatch));

        var result = service.dispatchAutomaticPackage(job, content, Set.of("practice"));

        assertThat(result.deliveredSignals())
                .extracting(InlineFeedbackChannel.DeliveredSignal::placement)
                .containsExactly(InlineFeedbackChannel.Placement.LOCATION_COMMENT, null);
    }

    @Test
    void shouldReadBackAnInFlightApprovedNoteBeforeRefusingAStaleApprovalAtTheCreate() {
        String recorded = "1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d";
        Feedback feedback = lineNotesOnlyFeedback(recorded);
        var unknown = InlineFeedbackChannel.DeliveredSignal.attempted(
                "approved:" + feedback.getId() + ":0", anchorOf(APPROVED_LINE_NOTES.get(0)));
        when(repository.findByDestinationKeyAndWorkspaceId("approved:" + feedback.getId(), 7L))
                .thenReturn(Optional.of(lineNotesOnlyDispatch(
                        feedback.getId(), FeedbackDispatchState.UNCERTAIN, 1, true, storedPlacements(unknown))));
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(policy.evaluateAtEgress(any(), any(), any(), any()))
                .thenAnswer(invocation -> PracticeFeedbackDeliveryPolicy.Decision.allowed(pullRequestAt(MOVED_HEAD)));
        var landed = lineSignal(feedback, 0, InlineFeedbackChannel.Disposition.PRESERVED_EXISTING);
        var refused = InlineFeedbackChannel.DeliveredSignal.notSent(
                "approved:" + feedback.getId() + ":1", anchorOf(APPROVED_LINE_NOTES.get(1)));
        var reviewedRevision = revisionCaptor();
        when(diffNotePoster.deliverPackage(
                        eq(job), any(), eq(APPROVED_LINE_NOTES), any(), reviewedRevision.capture(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(
                        List.of(landed, refused), false, false, false, true, false, List.of(), null));

        when(policy.currentReviewedRevision(job, recorded))
                .thenReturn(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED);

        var result = service.dispatchApproved(job, feedback);

        assertThat(reviewedRevision.getValue().get())
                .isEqualTo(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED);
        verify(policy).currentReviewedRevision(job, recorded);
        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SUPPRESSED);
        assertThat(result.suppressionReason()).isEqualTo(FeedbackSuppressionReason.APPROVAL_STALE);
        assertThat(result.deliveredSignals()).contains(landed);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Supplier<PracticeFeedbackDeliveryPolicy.ReviewedRevision>> revisionCaptor() {
        return ArgumentCaptor.forClass(Supplier.class);
    }

    /** Every note acknowledged: what the poster returns for a package it fully delivered or found. */
    private static DiffNotePoster.DiffNoteResult delivered(List<InlineFeedbackChannel.DeliveredSignal> signals) {
        return new DiffNotePoster.DiffNoteResult(signals, true, false, false, false, false, List.of(), null);
    }

    /** A proposal approved as line notes alone: it has no summary, so its body is absent. */
    private static Feedback lineNotesOnlyFeedback(@Nullable String reviewedRevision) {
        return Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(7L)
                .body(null)
                .reviewedRevision(reviewedRevision)
                .proposedPlacements(new ArrayList<>(APPROVED_LINE_NOTES.stream()
                        .map(note -> ProposedPlacement.inline(
                                note.body(), note.filePath(), note.startLine(), note.endLine(), note.deliveryKey()))
                        .toList()))
                .build();
    }

    private FeedbackDispatch lineNotesOnlyDispatch(UUID feedbackId, FeedbackDispatchState state, int attemptCount) {
        return lineNotesOnlyDispatch(
                feedbackId,
                state,
                attemptCount,
                false,
                JsonMapper.builder().build().createArrayNode());
    }

    /** The persisted approved package of {@link #lineNotesOnlyFeedback}: the empty summary convention and its notes. */
    private FeedbackDispatch lineNotesOnlyDispatch(
            UUID feedbackId,
            FeedbackDispatchState state,
            int attemptCount,
            boolean inlineWriteStarted,
            JsonNode deliveredPlacements) {
        FeedbackDispatch base = dispatch(state, false, attemptCount);
        var mapper = JsonMapper.builder().build();
        return new FeedbackDispatch(
                base.getId(),
                "approved:" + feedbackId,
                base.getWorkspaceId(),
                base.getAgentJobId(),
                feedbackId,
                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                state,
                "",
                base.getPracticeSlugs(),
                mapper.valueToTree(new ReviewResultParser.DeliveryContent(null, APPROVED_LINE_NOTES, List.of(), null)),
                deliveredPlacements,
                false,
                null,
                inlineWriteStarted,
                null,
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

    /** What the provider reports for the approved package's line note at {@code index}. */
    private static InlineFeedbackChannel.DeliveredSignal lineSignal(
            Feedback feedback, int index, InlineFeedbackChannel.Disposition disposition) {
        ReviewResultParser.DiffNote note = APPROVED_LINE_NOTES.get(index);
        return new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + feedback.getId() + ":" + index,
                FeedbackAnchor.DiffAnchor.singleLine(note.filePath(), note.startLine()),
                disposition,
                disposition == InlineFeedbackChannel.Disposition.FAILED ? null : "inline-ref-" + index,
                null);
    }

    private static JsonNode storedPlacements(InlineFeedbackChannel.DeliveredSignal... signals) {
        var placements = JsonMapper.builder().build().createArrayNode();
        for (var signal : signals) {
            var anchor = (FeedbackAnchor.DiffAnchor) signal.anchor();
            var placement = placements
                    .addObject()
                    .put("deliveryKey", signal.deliveryKey())
                    .put("path", anchor.filePath())
                    .put("startLine", anchor.newLineNumber())
                    .put("disposition", signal.disposition().name())
                    .put("externalRef", signal.externalRef());
            Boolean started = signal.writeMayHaveStarted();
            if (started != null) placement.put("writeMayHaveStarted", started);
            InlineFeedbackChannel.Placement where = signal.placement();
            if (where != null) placement.put("placement", where.name());
        }
        return placements;
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
                mapper.valueToTree(
                        new ReviewResultParser.DeliveryContent(approved.getBody(), List.of(), List.of(), null)),
                approved.getDeliveredPlacements(),
                approved.getWriteStarted(),
                approved.getWriteStartedAt(),
                approved.getInlineWriteStarted(),
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
                mapper.valueToTree(new ReviewResultParser.DeliveryContent(
                        "approved body",
                        List.of(new ReviewResultParser.DiffNote(
                                "src/Review.java", 12, null, "exact inline", "old-key", null)),
                        List.of(),
                        null)),
                mapper.valueToTree(List.of()),
                writeStarted,
                null,
                false,
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

    /** An automatic package with no summary and one inline note whose last recorded outcome is {@code disposition}. */
    private FeedbackDispatch pastBudgetInlineOnly(String disposition) {
        FeedbackDispatch base = dispatch(job, FeedbackDispatchState.UNCERTAIN, false, 0, "");
        var mapper = JsonMapper.builder().build();
        var placements = mapper.createArrayNode();
        placements
                .addObject()
                .put("deliveryKey", "observation:k1")
                .put("path", "src/Main.java")
                .put("startLine", 3)
                .put("disposition", disposition)
                .put("externalRef", disposition.equals("POSTED") ? "note-1" : null);
        return new FeedbackDispatch(
                base.getId(),
                base.getDestinationKey(),
                base.getWorkspaceId(),
                base.getAgentJobId(),
                null,
                base.getDestination(),
                FeedbackDispatchState.UNCERTAIN,
                "",
                base.getPracticeSlugs(),
                mapper.valueToTree(new ReviewResultParser.DeliveryContent(
                        null,
                        List.of(new ReviewResultParser.DiffNote(
                                "src/Main.java", 3, null, "note", "observation:k1", List.of("k1"))),
                        List.of(),
                        List.of())),
                placements,
                false,
                base.getCreatedAt(),
                true,
                null,
                null,
                null,
                null,
                base.getNextAttemptAt(),
                PracticeFeedbackDispatchService.MAX_ATTEMPTS,
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
                null,
                false,
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
                job, new ReviewResultParser.DeliveryContent(body, List.of(), List.of(), null), practiceSlugs);
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
                mapper.valueToTree(new ReviewResultParser.DeliveryContent(body, List.of(), List.of(), null)),
                mapper.valueToTree(List.of()),
                writeStarted,
                null,
                false,
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
                .put("pr_number", 42)
                .put("commit_sha", REVIEWED_HEAD));
        reviewJob.setWorkspace(workspace);
        return reviewJob;
    }

    private static PullRequest pullRequestAt(String head) {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setHeadRefOid(head);
        return pullRequest;
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
