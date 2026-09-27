package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.WithheldObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository.ProviderPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation.ProviderCopy;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationService;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * A correction against provider delivery, on a real database with a fake provider behind the real posters: a
 * correction never lands while a delivery citing it is in flight, an unacknowledged earlier write is found before
 * anything is withheld, and a posted copy ends corrected or honestly reported, never as absent.
 */
class ObservationInvalidationEgressIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final long ADMIN_ACCOUNT = 1L;
    private static final List<String> SCHEDULERS =
            List.of("observation-posted-copy-correction", "practice-feedback-dispatch-recovery");

    @Autowired
    private ObservationInvalidationService invalidationService;

    @Autowired
    private ObservationInvalidationRepository invalidationRepository;

    @Autowired
    private FeedbackPlacementRepository placementRepository;

    @Autowired
    private FeedbackDispatchRepository dispatchRepository;

    @Autowired
    private FeedbackDispatchStateMachine stateMachine;

    @Autowired
    private FeedbackDeliveryService feedbackDeliveryService;

    @Autowired
    private PracticeFeedbackCommentFormatter commentFormatter;

    @Autowired
    private FeedbackLedgerRecorder ledgerRecorder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final FakeProvider provider = new FakeProvider();
    private final PracticeFeedbackDeliveryPolicy policy = mock(PracticeFeedbackDeliveryPolicy.class);

    private PracticeFeedbackDispatchService dispatchService;
    private PostedCopyCorrector corrector;

    private Workspace workspace;
    private Practice practice;
    private AgentJob job;
    private User developer;
    private UUID observation;
    private DiffNote note;

    /** The real sweeps would race the hand-built ones below; their scheduler locks keep them out meanwhile. */
    @BeforeEach
    void holdTheSchedulers() {
        for (String name : SCHEDULERS) {
            jdbc.update("""
                    INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                    VALUES (?, now() + interval '1 hour', now(), 'egress-test')
                    ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_by = 'egress-test'
                    """, name);
        }
    }

    @AfterEach
    void releaseTheSchedulers() {
        jdbc.update("UPDATE shedlock SET lock_until = now() WHERE locked_by = 'egress-test'");
    }

    @BeforeEach
    void setUpReview() {
        PullRequestCommentPoster poster = new PullRequestCommentPoster(List.of(provider));
        dispatchService = new PracticeFeedbackDispatchService(
                dispatchRepository,
                policy,
                poster,
                transactionTemplate,
                objectMapper,
                feedbackRepository,
                new DiffNotePoster(poster, commentFormatter, List.of(provider)),
                stateMachine,
                invalidationRepository);
        corrector = new PostedCopyCorrector(
                invalidationRepository,
                observationRepository,
                dispatchRepository,
                placementRepository,
                agentJobRepository,
                poster);
        User owner = persistUser("egress-owner");
        workspace = createWorkspace("egress-ws", "Egress WS", "egress-org", AccountType.ORG, owner);
        developer = persistUser("egress-developer");
        practice = persistPractice(workspace, null, "closes-linked-issues", "Closes linked issues", null);
        job = persistPullRequestReview(workspace, 7, Instant.now());
        observation =
                observe(practice, job, 7L, developer, ObservationKind.OMISSION_GAP, Severity.MAJOR, Instant.now());
        note = noteAbout(observation, 3, "Closes #1 already.");
        when(policy.evaluatePullRequest(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.allowed(new PullRequest()));
    }

    private void invalidate() {
        invalidationService.setValidity(workspace.getId(), observation, ADMIN_ACCOUNT, false, "Issue #1 was open");
    }

    private String occurrenceKey(UUID observationId) {
        return observationRepository
                .findByIdAndWorkspaceId(observationId, workspace.getId())
                .orElseThrow()
                .getOccurrenceKey();
    }

    private DiffNote noteAbout(UUID observationId, int line, String body) {
        String occurrenceKey = occurrenceKey(observationId);
        return new DiffNote("src/Main.java", line, null, body, "observation:" + occurrenceKey, List.of(occurrenceKey));
    }

    private PracticeFeedbackDispatchService.Result dispatchAutomatic(String summary, List<DiffNote> notes) {
        List<String> summaryContributors = summary.isBlank() ? List.of() : List.of(occurrenceKey(observation));
        return dispatchService.dispatchAutomaticPackage(
                job, new DeliveryContent(summary, notes, List.of(), summaryContributors), Set.of(practice.getSlug()));
    }

    private Feedback approvedPackage() {
        return approvedPackage(List.of(ProposedPlacement.summary("Approved: closes #1 already.")));
    }

    private Feedback approvedPackage(List<ProposedPlacement> placements) {
        Feedback approved = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .recipientUserId(developer.getId())
                .aboutUserId(developer.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(1)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body("Approved: closes #1 already.")
                .proposedPlacements(placements)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
        bind(approved, observation);
        return feedbackRepository.findById(approved.getId()).orElseThrow();
    }

    /** Recovery's next pass, once the backoff an attempt left behind has elapsed. */
    private PracticeFeedbackDispatchService.Result recover(String destinationKey) {
        jdbc.update("UPDATE feedback_dispatch SET next_attempt_at = now() WHERE destination_key = ?", destinationKey);
        return dispatchService.recover(dispatch(destinationKey), job);
    }

    private FeedbackDispatch dispatch(String destinationKey) {
        return dispatchRepository
                .findByDestinationKeyAndWorkspaceId(destinationKey, workspace.getId())
                .orElseThrow();
    }

    @Test
    void shouldRefuseEveryWriteOnceACorrectionHasCommitted() {
        invalidate();

        assertThat(dispatchAutomatic("Closes #1 already.", List.of(note)).suppressionReason())
                .isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED);
        assertThat(dispatchService.dispatchApproved(job, approvedPackage()).suppressionReason())
                .isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED);
        assertThat(provider.comments).isEmpty();
        assertThat(provider.notes).isEmpty();
    }

    @Test
    void shouldDeliverAnAutomaticPackageWrittenOnlyFromAnotherObservationOfTheRun() {
        UUID other = observe(practice, job, 7L, developer, ObservationKind.OMISSION_GAP, Severity.MAJOR, Instant.now());
        DiffNote otherNote = noteAbout(other, 9, "Closes #2 already.");
        invalidate();

        PracticeFeedbackDispatchService.Result result = dispatchService.dispatchAutomaticPackage(
                job,
                new DeliveryContent("Closes #2 already.", List.of(otherNote), List.of(), otherNote.contributors()),
                Set.of(practice.getSlug()));

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(provider.comments).hasSize(1);
        assertThat(provider.notes).containsKey(String.valueOf(otherNote.deliveryKey()));
    }

    @Test
    void shouldCorrectOnlyTheObservationsAnAutomaticPackageWasWrittenFrom() {
        String key = "review:" + job.getId();
        UUID written =
                observe(practice, job, 7L, developer, ObservationKind.OMISSION_GAP, Severity.MAJOR, Instant.now());
        provider.duringWrite = this::invalidate;

        PracticeFeedbackDispatchService.Result result = dispatchService.dispatchAutomaticPackage(
                job,
                new DeliveryContent(
                        "Closes #2 already.",
                        List.of(),
                        List.of(new WithheldObservation(
                                occurrenceKey(observation), FeedbackSuppressionReason.COMPOSER_WITHHELD)),
                        List.of(occurrenceKey(written))),
                Set.of(practice.getSlug()));
        provider.duringWrite = () -> {};
        settleCorrections();

        assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.NONE);

        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        invalidationService.setValidity(workspace.getId(), written, ADMIN_ACCOUNT, false, "Issue #2 was open");
        settleCorrections();

        assertThat(provider.comments.get("summary-1")).startsWith("> **Correction:**");
        assertThat(active(written).getProviderCopy()).isEqualTo(ProviderCopy.UPDATED);
    }

    @Test
    void shouldRefuseACorrectionWhileAnAutomaticDeliveryCitingItIsWriting() {
        provider.duringWrite = this::expectCorrectionRefused;

        assertThat(dispatchAutomatic("", List.of(note)).status())
                .isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(invalidationRepository.findHistory(workspace.getId(), observation))
                .isEmpty();
    }

    @Test
    void shouldRefuseACorrectionWhileAnApprovedDeliveryCitingItIsWriting() {
        provider.duringWrite = this::expectCorrectionRefused;

        assertThat(dispatchService.dispatchApproved(job, approvedPackage()).status())
                .isEqualTo(PracticeFeedbackDispatchService.Result.Status.SENT);
        assertThat(invalidationRepository.findHistory(workspace.getId(), observation))
                .isEmpty();
    }

    private void expectCorrectionRefused() {
        assertThatThrownBy(this::invalidate)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void shouldAdmitADeliveryOnlyAfterAnUncommittedCorrectionAndThenRefuse() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger correctionBackend = new AtomicInteger();
        CompletableFuture<Void> correction =
                CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
                    invalidate();
                    correctionBackend.set(java.util.Objects.requireNonNull(
                            jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                    locked.countDown();
                    awaitLatch(commit);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<PracticeFeedbackDispatchService.Result> delivery =
                CompletableFuture.supplyAsync(() -> dispatchAutomatic("Closes #1 already.", List.of(note)));
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                    Boolean.class,
                    correctionBackend.get()));
        });
        assertThat(delivery).isNotDone();
        commit.countDown();
        correction.get(10, TimeUnit.SECONDS);

        assertThat(delivery.get(10, TimeUnit.SECONDS).suppressionReason())
                .isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED);
        assertThat(provider.comments).isEmpty();
        assertThat(provider.notes).isEmpty();
    }

    @Test
    void shouldFindAnAutomaticSummaryWhoseAcknowledgementWasLostAndCorrectIt() {
        provider.loseResponse = true;
        dispatchAutomatic("Closes #1 already.", List.of(note));
        assertThat(dispatch("review:" + job.getId()).getWriteStarted()).isTrue();
        assertThat(dispatch("review:" + job.getId()).getInlineWriteStarted()).isFalse();
        provider.loseResponse = false;
        invalidate();

        PracticeFeedbackDispatchService.Result recovered = recover("review:" + job.getId());
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch("review:" + job.getId()));
        settleCorrections();

        assertThat(recovered.suppressionReason()).isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED);
        assertThat(recovered.externalRef()).isEqualTo("summary-1");
        assertThat(provider.notes).isEmpty();
        assertThat(provider.comments.get("summary-1")).startsWith("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UPDATED);
    }

    @Test
    void shouldFindAnApprovedSummaryWhoseAcknowledgementWasLostByItsOwnMarker() {
        Feedback approved = approvedPackage();
        provider.loseResponse = true;
        dispatchService.dispatchApproved(job, approved);
        provider.loseResponse = false;
        invalidate();

        PracticeFeedbackDispatchService.Result recovered = recover("approved:" + approved.getId());

        assertThat(recovered.suppressionReason()).isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED);
        assertThat(recovered.externalRef()).isEqualTo("summary-1");
    }

    @Test
    void shouldFindInlineNotesWhoseAcknowledgementWasLostAndReportThemRemaining() {
        provider.loseResponse = true;
        dispatchAutomatic("", List.of(note));
        provider.loseResponse = false;
        invalidate();

        recover("review:" + job.getId());
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch("review:" + job.getId()));
        settleCorrections();

        assertThat(dispatch("review:" + job.getId()).getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    @Test
    void shouldKeepAHeldSummaryPostUnresolvedAcrossLeaseExpiryUntilItLandsAndIsCorrected() throws Exception {
        String key = "review:" + job.getId();
        CompletableFuture<PracticeFeedbackDispatchService.Result> held =
                holdWrite(() -> dispatchAutomatic("Closes #1 already.", List.of()));
        expireLease(key);
        invalidate();

        recover(key);

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        settleCorrections();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);

        releaseHeldWrite(held);
        recover(key);
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(dispatch(key).getDeliveredExternalRef()).isEqualTo("summary-1");
        assertThat(provider.comments.get("summary-1")).startsWith("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UPDATED);
    }

    @Test
    void shouldKeepAHeldInlinePostUnresolvedAcrossLeaseExpiryUntilItLandsAndIsReported() throws Exception {
        String key = "review:" + job.getId();
        CompletableFuture<PracticeFeedbackDispatchService.Result> held =
                holdWrite(() -> dispatchAutomatic("", List.of(note)));
        expireLease(key);
        invalidate();

        recover(key);

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        settleCorrections();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);

        releaseHeldWrite(held);
        recover(key);
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    @Test
    void shouldKeepCheckingAWritePastItsWindowAndCorrectItWhenItLandsLate() throws Exception {
        String key = "review:" + job.getId();
        CompletableFuture<PracticeFeedbackDispatchService.Result> held =
                holdWrite(() -> dispatchAutomatic("Closes #1 already.", List.of()));
        expireLease(key);
        invalidate();
        jdbc.update(
                "UPDATE feedback_dispatch SET write_started_at = now() - interval '25 hours' WHERE destination_key = ?",
                key);

        recover(key);
        settleCorrections();

        FeedbackDispatch waiting = dispatch(key);
        assertThat(waiting.getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(waiting.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(5 * 3600));
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UNRESOLVED);

        releaseHeldWrite(held);
        recover(key);
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(provider.comments.get("summary-1")).startsWith("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UPDATED);
    }

    @Test
    void shouldLookUpAnInlineNoteTheAdapterReportedFailedAfterTheProviderAcceptedIt() {
        String key = "review:" + job.getId();
        provider.failAfterAccept = true;
        dispatchAutomatic("", List.of(note));
        provider.failAfterAccept = false;
        invalidate();

        recover(key);
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(provider.notes).isNotEmpty();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    /** A retry withheld for another reason never looked for what the failed attempt may have posted. */
    @Test
    void shouldReportUnresolvedWhenAnUnconfirmedWriteWasLaterWithheldForAnotherReason() {
        String key = "review:" + job.getId();
        provider.failAfterAccept = true;
        dispatchAutomatic("", List.of(note));
        provider.failAfterAccept = false;
        when(policy.evaluatePullRequest(any(), any(), any(), any()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.suppressed(
                        FeedbackSuppressionReason.RECIPIENT_OPTED_OUT));
        recover(key);
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch(key));

        invalidate();
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UNRESOLVED);
    }

    @Test
    void shouldCorrectAKnownSummaryWhileAnInlineNoteOfThePackageIsStillUnconfirmed() {
        Feedback approved = approvedPackage(List.of(
                ProposedPlacement.summary("Approved: closes #1 already."),
                new ProposedPlacement(PlacementType.INLINE, "Closes #1 already.", "src/Main.java", 3, null, null)));
        String key = "approved:" + approved.getId();
        provider.failAfterAccept = true;
        PracticeFeedbackDispatchService.Result first = dispatchService.dispatchApproved(job, approved);
        provider.failAfterAccept = false;
        ledgerRecorder.recordApprovedPlacements(approved, first.externalRef(), first.deliveredSignals());
        provider.lookupFails = true;
        invalidate();

        recover(key);
        settleCorrections();
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(provider.comments.get("summary-1"))
                .startsWith("> **Correction:**")
                .containsOnlyOnce("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);

        provider.lookupFails = false;
        PracticeFeedbackDispatchService.Result reconciled = recover(key);
        dispatchService.projectRecovered(
                dispatch(key),
                () -> ledgerRecorder.recordApprovedPlacements(
                        approved, reconciled.externalRef(), reconciled.deliveredSignals()));
        settleCorrections();

        assertThat(provider.comments.get("summary-1")).containsOnlyOnce("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    @Test
    void shouldCorrectAKnownAutomaticSummaryWhileAnInlineNoteOfThePackageIsStillUnconfirmed() {
        String key = "review:" + job.getId();
        provider.failAfterAccept = true;
        dispatchAutomatic("Closes #1 already.", List.of(note));
        provider.failAfterAccept = false;
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        provider.lookupFails = true;
        invalidate();

        recover(key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        settleCorrections();
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(provider.comments.get("summary-1"))
                .startsWith("> **Correction:**")
                .containsOnlyOnce("> **Correction:**")
                .contains("Closes #1 already.");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);
        assertThat(ledgerPlacements(0)).containsExactly("SUMMARY summary-1");

        provider.lookupFails = false;
        recover(key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(provider.comments.get("summary-1")).containsOnlyOnce("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
        assertThat(ledgerPlacements(0)).containsExactlyInAnyOrder("SUMMARY summary-1", "INLINE note-1");
    }

    @Test
    void shouldKeepTheNotesALookupFoundWhileAnotherIsStillUnconfirmed() {
        String key = "review:" + job.getId();
        UUID other = observe(practice, job, 7L, developer, ObservationKind.OMISSION_GAP, Severity.MAJOR, Instant.now());
        DiffNote otherNote = noteAbout(other, 9, "Closes #2 already.");
        provider.failAfterAccept = true;
        dispatchAutomatic("", List.of(note, otherNote));
        provider.failAfterAccept = false;
        String late = provider.notes.remove(String.valueOf(otherNote.deliveryKey()));
        invalidate();

        recover(key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(ledgerPlacements(0)).containsExactly("INLINE note-1");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);

        provider.notes.put(String.valueOf(otherNote.deliveryKey()), late);
        recover(key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        recover(key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(ledgerPlacements(0)).containsExactlyInAnyOrder("INLINE note-1", "INLINE " + late);
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    @Test
    void shouldReportAnAutomaticInlineNotePostedWithoutAnIdAsUnresolved() {
        String key = "review:" + job.getId();
        provider.omitIds = true;
        dispatchAutomatic("", List.of(note));
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        ledgerRecorder.recordWithoutConversation(
                job,
                new DeliveryContent("", List.of(note), List.of(), List.of()),
                ArtifactKinds.PULL_REQUEST,
                dispatchService.deliveredSignals(dispatch(key)),
                null,
                true);
        invalidate();
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.SENT);
        assertThat(ledgerPlacements(0)).containsExactly("INLINE (no id)");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UNRESOLVED);
    }

    @Test
    void shouldCorrectAnApprovedSummaryButReportItsInlineNotePostedWithoutAnIdAsUnresolved() {
        Feedback approved = approvedPackage(List.of(
                ProposedPlacement.summary("Approved: closes #1 already."),
                new ProposedPlacement(PlacementType.INLINE, "Closes #1 already.", "src/Main.java", 3, null, null)));
        provider.omitIds = true;
        PracticeFeedbackDispatchService.Result sent = dispatchService.dispatchApproved(job, approved);
        dispatchService.projectRecovered(
                dispatch("approved:" + approved.getId()),
                () -> ledgerRecorder.recordApprovedPlacements(approved, sent.externalRef(), sent.deliveredSignals()));
        ledgerRecorder.recordApprovedPlacements(approved, sent.externalRef(), sent.deliveredSignals());
        invalidate();
        settleCorrections();

        assertThat(dispatch("approved:" + approved.getId()).getState()).isEqualTo(FeedbackDispatchState.SENT);
        assertThat(ledgerPlacements(1)).containsExactlyInAnyOrder("SUMMARY summary-1", "INLINE (no id)");
        assertThat(provider.comments.get("summary-1")).containsOnlyOnce("> **Correction:**");
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.UNRESOLVED);
    }

    private List<@Nullable String> ledgerPlacements(int position) {
        return jdbc.queryForList("""
                SELECT pl.placement_type || ' ' || COALESCE(pl.posted_comment_ref, '(no id)') FROM feedback f
                JOIN feedback_placement pl ON pl.feedback_id = f.id
                WHERE f.agent_job_id = ? AND f.workspace_id = ? AND f.position = ?
                """, String.class, job.getId(), workspace.getId(), position);
    }

    /** The scheduled sweep over this workspace, due now, as it would next run. */
    private void settleCorrections() {
        jdbc.update(
                "UPDATE observation_invalidation SET provider_copy_retry_at = now() - interval '1 second'"
                        + " WHERE workspace_id = ?",
                workspace.getId());
        corrector.settleDue(Instant.now());
    }

    private final CountDownLatch writing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** Starts {@code delivery} and returns once its provider write is under way and held there. */
    private CompletableFuture<PracticeFeedbackDispatchService.Result> holdWrite(
            java.util.function.Supplier<PracticeFeedbackDispatchService.Result> delivery) throws Exception {
        provider.duringWrite = () -> {
            writing.countDown();
            awaitLatch(release);
        };
        CompletableFuture<PracticeFeedbackDispatchService.Result> held = CompletableFuture.supplyAsync(delivery);
        assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
        return held;
    }

    /** Lets the held write land; its own attempt then finds its claim taken over and records nothing. */
    private void releaseHeldWrite(CompletableFuture<PracticeFeedbackDispatchService.Result> held) throws Exception {
        provider.duringWrite = () -> {};
        release.countDown();
        held.get(10, TimeUnit.SECONDS);
    }

    private void expireLease(String destinationKey) {
        jdbc.update(
                "UPDATE feedback_dispatch SET lease_expires_at = now() - interval '1 second' WHERE destination_key = ?",
                destinationKey);
    }

    @Test
    void shouldStayPendingWhileAnEarlierInlineWriteCannotBeLookedUp() {
        provider.loseResponse = true;
        dispatchAutomatic("", List.of(note));
        provider.loseResponse = false;
        provider.lookupFails = true;
        invalidate();

        recover("review:" + job.getId());

        FeedbackDispatch unresolved = dispatch("review:" + job.getId());
        assertThat(unresolved.getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(unresolved.getWriteStarted()).isFalse();
        assertThat(unresolved.getInlineWriteStarted()).isTrue();
        settleCorrections();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);

        for (int attempt = 0; attempt < PracticeFeedbackDispatchService.MAX_ATTEMPTS; attempt++) {
            recover("review:" + job.getId());
        }
        FeedbackDispatch stillLooking = dispatch("review:" + job.getId());
        assertThat(stillLooking.getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(stillLooking.getAttemptCount()).isGreaterThan(PracticeFeedbackDispatchService.MAX_ATTEMPTS);
        assertThat(stillLooking.getWriteStarted()).isFalse();

        provider.lookupFails = false;
        recover("review:" + job.getId());
        feedbackDeliveryService.projectAutomaticPackage(job, dispatch("review:" + job.getId()));
        settleCorrections();

        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    @Test
    void shouldWithholdWithNothingPostedWhenOnlyALookupRanBeforeTheCorrection() {
        String key = "review:" + job.getId();
        provider.lookupFails = true;
        assertThat(dispatchAutomatic("Closes #1 already.", List.of(note)).status())
                .isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        provider.lookupFails = false;
        invalidate();

        recover(key);

        FeedbackDispatch withheld = dispatch(key);
        assertThat(withheld.getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(withheld.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED.name());
        assertThat(withheld.getWriteStarted()).isFalse();
        assertThat(withheld.getInlineWriteStarted()).isFalse();
        assertThat(provider.comments).isEmpty();
        assertThat(provider.notes).isEmpty();
        feedbackDeliveryService.recordAutomaticPackage(job, withheld);
        settleCorrections();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.NONE);
    }

    @Test
    void shouldKeepLookingForAnUntrackedInlineNoteAfterAFailedPackageIsReset() {
        String key = "review:" + job.getId();
        provider.failAfterAccept = true;
        dispatchAutomatic("", List.of(note));
        provider.failAfterAccept = false;
        jdbc.update(
                "UPDATE feedback_dispatch SET state = 'FAILED', inline_write_started = NULL WHERE destination_key = ?",
                key);
        transactionTemplate.executeWithoutResult(
                status -> dispatchRepository.resetFailedAutomaticPackage(job.getId(), workspace.getId()));
        provider.lookupFails = true;
        invalidate();

        recover(key);

        FeedbackDispatch pending = dispatch(key);
        assertThat(pending.getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(pending.getInlineWriteStarted()).isNull();
        assertThat(provider.notes).hasSize(1);

        provider.lookupFails = false;
        recover(key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        settleCorrections();

        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.SUPPRESSED);
        assertThat(provider.notes).hasSize(1);
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);
    }

    @Test
    void shouldReportNothingPostedForAnUntrackedPackageWithNoInlineNotesThatNeverWrote() {
        String key = "review:" + job.getId();
        provider.lookupFails = true;
        dispatchAutomatic("Closes #1 already.", List.of());
        for (int attempt = 1; attempt < PracticeFeedbackDispatchService.MAX_ATTEMPTS; attempt++) {
            recover(key);
        }
        provider.lookupFails = false;
        assertThat(dispatch(key).getState()).isEqualTo(FeedbackDispatchState.FAILED);
        assertThat(dispatch(key).getWriteStarted()).isFalse();
        jdbc.update("UPDATE feedback_dispatch SET inline_write_started = NULL WHERE destination_key = ?", key);
        feedbackDeliveryService.recordAutomaticPackage(job, dispatch(key));
        invalidate();

        settleCorrections();

        assertThat(provider.comments).isEmpty();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.NONE);
    }

    @Test
    void shouldCorrectAPostedSummaryAndRestoreItsText() {
        Feedback delivered = deliveredWith(observation, 0, PlacementType.SUMMARY, "summary-1");
        post(place(delivered, PlacementType.INLINE, "note-1"));

        invalidate();
        settleCorrections();

        assertThat(provider.comments.get("summary-1"))
                .startsWith("> **Correction:** a workspace admin marked what this review says about "
                        + "**Closes linked issues** as incorrect. Please disregard that part.\n\nCloses #1 already.");
        assertThat(latest().getProviderCopy()).isEqualTo(ProviderCopy.INLINE_REMAINS);

        invalidationService.setValidity(workspace.getId(), observation, ADMIN_ACCOUNT, true, "It was right");
        settleCorrections();

        assertThat(provider.comments.get("summary-1")).startsWith("Closes #1 already.");
        assertThat(latest().getProviderCopy()).isEqualTo(ProviderCopy.UPDATED);
    }

    @Test
    void shouldReportASummaryTheProviderCannotEditAsUnresolved() {
        deliveredWith(observation, 0, PlacementType.SUMMARY, "summary-1");
        provider.edit = id -> SummaryChannel.UpdateOutcome.unsupported();

        invalidate();
        settleCorrections();

        assertThat(latest().getProviderCopy()).isEqualTo(ProviderCopy.UNRESOLVED);
    }

    /** Fifty corrections a provider keeps refusing cannot starve the one after them. */
    @Test
    void shouldReachALaterCorrectionWhileEarlierOnesKeepFailing() {
        List<UUID> failing = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            failing.add(invalidatedWithSummary("failing-" + i, 10 + i));
        }
        UUID healthy = invalidatedWithSummary("healthy", 60);
        provider.edit = id -> id.equals("healthy")
                ? SummaryChannel.UpdateOutcome.edited(new SummaryChannel.SummaryHandle(id))
                : SummaryChannel.UpdateOutcome.transientFailure("rate limited");
        Instant now = Instant.now();

        // Rows other tests left pending may be due as well; each sweep moves every row it visits behind the rest.
        for (int sweep = 0; sweep < 5 && active(healthy).getProviderCopy() == ProviderCopy.PENDING; sweep++) {
            corrector.settleDue(now);
        }

        assertThat(active(healthy).getProviderCopy()).isEqualTo(ProviderCopy.UPDATED);
        assertThat(failing).allSatisfy(id -> {
            ObservationInvalidation stillFailing = active(id);
            assertThat(stillFailing.getProviderCopy()).isEqualTo(ProviderCopy.PENDING);
            assertThat(stillFailing.getProviderCopyRetryAt()).isAfter(now);
        });
    }

    private UUID invalidatedWithSummary(String commentRef, int position) {
        UUID id = observe(practice, job, 7L, developer, ObservationKind.OMISSION_GAP, Severity.MAJOR, Instant.now());
        deliveredWith(id, position, PlacementType.SUMMARY, commentRef);
        invalidationService.setValidity(workspace.getId(), id, ADMIN_ACCOUNT, false, "Wrong");
        return id;
    }

    @Test
    void shouldStayPendingWhileTheProviderEditCanOnlyBeRetried() {
        deliveredWith(observation, 0, PlacementType.SUMMARY, "summary-1");
        provider.edit = id -> SummaryChannel.UpdateOutcome.transientFailure("rate limited");

        invalidate();

        settleCorrections();
        assertThat(active(observation).getProviderCopy()).isEqualTo(ProviderCopy.PENDING);
        assertThat(latest().getProviderCopy()).isEqualTo(ProviderCopy.PENDING);
    }

    private Feedback deliveredWith(UUID observationId, int position, PlacementType type, String commentRef) {
        Feedback delivered = persistFeedback(
                job,
                developer,
                FeedbackChannel.IN_CONTEXT,
                position,
                FeedbackDeliveryState.DELIVERED,
                "Closes #1 already.",
                Instant.now());
        bind(delivered, observationId);
        post(place(delivered, type, commentRef));
        return delivered;
    }

    private ObservationInvalidation active(UUID observationId) {
        return invalidationRepository
                .findActive(workspace.getId(), observationId)
                .orElseThrow();
    }

    private ObservationInvalidation latest() {
        return invalidationRepository
                .findHistory(workspace.getId(), observation)
                .getFirst();
    }

    private void post(ProviderPlacement placement) {
        transactionTemplate.executeWithoutResult(
                status -> placementRepository.insertProviderPlacementIfAbsent(placement));
    }

    private static ProviderPlacement place(Feedback feedback, PlacementType type, String ref) {
        boolean inline = type == PlacementType.INLINE;
        return new ProviderPlacement(
                UUID.randomUUID(),
                feedback.getId(),
                type.name(),
                inline ? "LINE" : null,
                inline ? "src/Main.java" : null,
                inline ? 3 : null,
                null,
                inline ? "NEW" : null,
                ref);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * GitHub as delivery sees it: the comments and inline notes it holds, and the ways a provider misleads a
     * client — a write that lands while its response is lost, a lookup that cannot answer, an edit it refuses.
     */
    private static final class FakeProvider implements SummaryChannel, InlineFeedbackChannel {

        final Map<String, String> comments = new ConcurrentHashMap<>();
        final Map<String, String> notes = new ConcurrentHashMap<>();
        Runnable duringWrite = () -> {};
        boolean loseResponse;
        boolean failAfterAccept;
        boolean lookupFails;
        boolean omitIds;
        Function<String, UpdateOutcome> edit = id -> UpdateOutcome.edited(new SummaryHandle(id));

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITHUB;
        }

        @Override
        public String formatPullRequestSubjectId(String repoFullName, int prNumber) {
            return repoFullName + "#" + prNumber;
        }

        @Override
        public SummaryHandle postSummary(FeedbackTarget target, FeedbackContent content) {
            duringWrite.run();
            String id = "summary-" + (comments.size() + 1);
            comments.put(id, content.externalBody());
            if (loseResponse) {
                throw new FeedbackDeliveryException("Response lost after the provider accepted the comment");
            }
            return new SummaryHandle(id);
        }

        @Override
        public ExistingSummaryLookup findExistingSummary(FeedbackTarget target, String marker) {
            if (lookupFails) {
                return ExistingSummaryLookup.unknown();
            }
            return comments.entrySet().stream()
                    .filter(comment -> comment.getValue().contains(marker))
                    .findFirst()
                    .map(comment -> ExistingSummaryLookup.found(new SummaryHandle(comment.getKey())))
                    .orElse(ExistingSummaryLookup.absent());
        }

        @Override
        public UpdateOutcome updateSummary(FeedbackTarget target, String externalId, FeedbackContent content) {
            UpdateOutcome outcome = edit.apply(externalId);
            if (outcome.kind() == UpdateOutcome.Kind.EDITED) {
                comments.put(externalId, content.externalBody());
            }
            return outcome;
        }

        @Override
        public InlineResult postInlineFeedback(FeedbackTarget target, List<InlineFeedback> feedback) {
            duringWrite.run();
            List<DeliveredSignal> signals = new ArrayList<>();
            for (InlineFeedback item : feedback) {
                String id = "note-" + (notes.size() + 1);
                notes.put(String.valueOf(item.deliveryKey()), id);
                // What an adapter reports when the provider accepted the note but its response names no comment.
                signals.add(new DeliveredSignal(
                        item.deliveryKey(), item.anchor(), Disposition.POSTED, omitIds ? null : id, null));
            }
            if (loseResponse) {
                throw new FeedbackDeliveryException("Response lost after the provider accepted the notes");
            }
            if (failAfterAccept) {
                // What a real adapter returns when the mutation's response is lost: the key, marked failed.
                return new InlineResult(
                        0,
                        feedback.size(),
                        feedback.stream()
                                .map(item -> new DeliveredSignal(
                                        item.deliveryKey(), item.anchor(), Disposition.FAILED, null, null))
                                .toList());
            }
            return new InlineResult(signals.size(), 0, signals);
        }

        @Override
        public void clearStaleFeedback(FeedbackTarget target, String marker) {}

        @Override
        public @Nullable List<DeliveredSignal> findPosted(
                FeedbackTarget target, List<InlineFeedback> feedback, boolean immutablePackage) {
            if (lookupFails) {
                return null;
            }
            List<DeliveredSignal> found = new ArrayList<>();
            for (InlineFeedback item : feedback) {
                String id = notes.get(String.valueOf(item.deliveryKey()));
                if (id != null) {
                    found.add(new DeliveredSignal(
                            item.deliveryKey(), item.anchor(), Disposition.PRESERVED_EXISTING, id, null));
                }
            }
            return found;
        }
    }
}
